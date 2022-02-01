/*
 * Copyright (c) 2022 Tada AB and other contributors, as listed below.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the The BSD 3-Clause License
 * which accompanies this distribution, and is available at
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Contributors:
 *   Chapman Flack
 */
package org.postgresql.pljava.pg;

import org.postgresql.pljava.model.*;
import static org.postgresql.pljava.model.RegType.RECORD;

import org.postgresql.pljava.sqlgen.Lexicals.Identifier.Simple;

import static org.postgresql.pljava.internal.Backend.doInPG;
import static org.postgresql.pljava.internal.Backend.threadMayEnterPG;
import org.postgresql.pljava.internal.DualState;

import static org.postgresql.pljava.pg.CatalogObjectImpl.*;
import static org.postgresql.pljava.pg.ModelConstants.*;
import static org.postgresql.pljava.pg.DatumUtils.addressOf;
import static org.postgresql.pljava.pg.DatumUtils.asReadOnlyNativeOrder;

import java.nio.ByteBuffer;
import static java.nio.ByteOrder.nativeOrder;

import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;

import java.util.AbstractList;
import java.util.List;
import java.util.Map;

import java.util.concurrent.ConcurrentHashMap;

import java.util.function.BiFunction;
import java.util.function.IntSupplier;
import java.util.function.ToIntBiFunction;

/**
 * Implementation of {@link TupleDescriptor TupleDescriptor}.
 *<p>
 * A {@link Cataloged Cataloged} descriptor corresponds to a known composite
 * type declared in the PostgreSQL catalogs; its {@link #rowType rowType} method
 * returns that type. A {@link Blessed Blessed} descriptor has been constructed
 * on the fly and then interned in the type cache, such that the type
 * {@code RECORD} and its type modifier value will identify it uniquely for
 * the life of the backend; {@code rowType} will return the corresponding
 * {@link RegTypeImpl.Blessed} instance. An {@link Ephemeral Ephemeral}
 * descriptor has been constructed ad hoc and not interned; {@code rowType} will
 * return {@link RegType#RECORD RECORD} itself, which isn't a useful identifier
 * (many such ephemeral descriptors, all different, could exist at once).
 * An ephemeral descriptor is only useful as long as a reference to it is held.
 *<p>
 * A {@code Cataloged} descriptor can be obtained from the PG {@code relcache}
 * or the {@code typcache}, should respond to cache invalidation for
 * the corresponding relation, and is reference-counted, so the count should be
 * incremented when cached here, and decremented/released if this instance
 * goes unreachable from Java.
 *<p>
 * A {@code Blessed} descriptor can be obtained from the PG {@code typcache}
 * by {@code lookup_rowtype_tupdesc}. No invalidation logic is needed, as it
 * will persist, and its identifying typmod will remain unique, for the life of
 * the backend. It may or may not be reference-counted.
 *<p>
 * An {@code Ephemeral} tuple descriptor may need to be copied out of
 * a short-lived memory context where it is found, either into a longer-lived
 * context (and invalidated when that context is), or onto the Java heap and
 * used until GC'd.
 */
abstract class TupleDescImpl extends AbstractList<Attribute>
implements TupleDescriptor
{
	private final ByteBuffer m_td;
	private final Attribute[] m_attrs;
	private final Map<Simple,Attribute> m_byName;
	private final State m_state;

	/**
	 * A "getAndAdd" (with just plain memory effects, as it will only be used on
	 * the PG thread) tailored to the width of the tdrefcount field (which is,
	 * oddly, declared as C int rather than a specific-width type).
	 */
	private static final ToIntBiFunction<ByteBuffer,Integer> s_getAndAddPlain;

	static
	{
		if ( 4 == SIZEOF_TUPLEDESC_TDREFCOUNT )
		{
			s_getAndAddPlain = (b,i) ->
			{
				int count = b.getInt(OFFSET_TUPLEDESC_TDREFCOUNT);
				b.putInt(OFFSET_TUPLEDESC_TDREFCOUNT, count + i);
				return count;
			};
		}
		else
			throw new ExceptionInInitializerError(
				"Implementation needed for platform with " +
				"sizeof TupleDesc->tdrefcount = " +SIZEOF_TUPLEDESC_TDREFCOUNT);
	}

	/**
	 * Address of the native tuple descriptor (not supported on
	 * an {@code Ephemeral} instance).
	 */
	long address() throws SQLException
	{
		try
		{
			m_state.pin();
			return m_state.address();
		}
		finally
		{
			m_state.unpin();
		}
	}

	/**
	 * Slice off the portion of the buffer representing one attribute.
	 *<p>
	 * Only called by {@code AttributeImpl}.
	 */
	ByteBuffer slice(int index)
	{
		int len = SIZEOF_FORM_PG_ATTRIBUTE;
		int off = OFFSET_TUPLEDESC_ATTRS + len * index;
		len = ATTRIBUTE_FIXED_PART_SIZE; // TupleDesc hasn't got the whole thing
		// Java 13: m_td.slice(off, len).order(m_td.order())
		ByteBuffer bnew = m_td.duplicate();
		bnew.position(off).limit(off + len);
		return bnew.slice().order(m_td.order());
	}

	private TupleDescImpl(
		ByteBuffer td, boolean useState,
		BiFunction<TupleDescImpl,Integer,Attribute> ctor)
	{
		assert threadMayEnterPG() : "TupleDescImpl construction thread";

		m_state = useState ? new State(this, td) : null;
		m_td = asReadOnlyNativeOrder(td);
		Attribute[] attrs =
			new Attribute [ (m_td.capacity() - OFFSET_TUPLEDESC_ATTRS)
							/ SIZEOF_FORM_PG_ATTRIBUTE ];

		for ( int i = 0 ; i < attrs.length ; ++ i )
			attrs[i] = ctor.apply(this, 1 + i);

		m_attrs = attrs;

		/*
		 * A stopgap. There is probably a lighter-weight API to be designed
		 * that doesn't assume every TupleDescriptor has a HashMap. Application
		 * code often knows what all the names are of the attributes it will be
		 * interested in.
		 */
		m_byName = new ConcurrentHashMap<>(m_attrs.length);
	}

	/**
	 * Constructor used only by OfType to produce a synthetic tuple descriptor
	 * with one element of a specified RegType.
	 */
	private TupleDescImpl(RegType type)
	{
		m_state = null;
		m_td = null;
		m_attrs = new Attribute[] { new AttributeImpl.OfType(this, type) };
		m_byName = new ConcurrentHashMap<>(m_attrs.length);
	}

	/**
	 * Return a {@code TupleDescImpl} given a byte buffer that maps a PostgreSQL
	 * {@code TupleDesc} structure.
	 *<p>
	 * This method is called from native code, and assumes the caller has not
	 * (or not knowingly) obtained the descriptor directly from the type cache,
	 * so if it is not reference-counted (its count is -1) it will be assumed
	 * unsafe to directly cache. In that case, if it represents a cataloged
	 * or interned ("blessed") descriptor, we will get one directly from the
	 * cache and return that, or if it is ephemeral, we will return one based
	 * on a defensive copy.
	 *<p>
	 * If the descriptor is reference-counted, and we use it (that is, we do not
	 * find an existing version in our cache), we increment the reference count
	 * here. That does <em>not</em> have the effect of requesting leak warnings
	 * at the exit of PostgreSQL's current resource owner, because we have every
	 * intention of hanging on to it longer, until GC or an invalidation
	 * callback tells us not to.
	 *<p>
	 * While we can just read the type oid, typmod, and reference count through
	 * the byte buffer, as long as the only caller is C code, it saves some fuss
	 * just to have it pass those values. If the C caller has the relation oid
	 * handy also, it can pass that as well and save a lookup here.
	 */
	private static TupleDescriptor fromByteBuffer(
		ByteBuffer td, int typoid, int typmod, int reloid, int refcount)
	{
		TupleDescriptor.Interned result;

		td.order(nativeOrder());

		/*
		 * Case 1: if the type is not RECORD, it's a cataloged composite type.
		 * Build an instance of Cataloged (unless the implicated RegClass has
		 * already got one).
		 */
		if ( RECORD.oid() != typoid )
		{
			RegTypeImpl t =
				(RegTypeImpl)Factory.formMaybeModifiedType(typoid, typmod);

			RegClassImpl c =
				(RegClassImpl)( InvalidOid == reloid ? t.relation()
					: Factory.staticFormObjectId(RegClass.CLASSID, reloid) );

			assert c.isValid() : "Cataloged row type without matching RegClass";

			if ( -1 == refcount ) // don't waste time on an ephemeral copy.
				return c.tupleDescriptor(); // just go get the real one.

			TupleDescriptor.Interned[] holder = c.m_tupDescHolder;
			if ( null != holder )
			{
				result = holder[0];
				assert null != result : "disagree whether RegClass has desc";
				return result;
			}

			holder = new TupleDescriptor.Interned[1];
			/*
			 * The constructor assumes the reference count has already been
			 * incremented to account for the reference constructed here.
			 */
			s_getAndAddPlain.applyAsInt(td, 1);
			holder[0] = result = new Cataloged(td, c);
			c.m_tupDescHolder = holder;
			return result;
		}

		/*
		 * Case 2: if RECORD with a modifier, it's an interned tuple type.
		 * Build an instance of Blessed (unless the implicated RegType has
		 * already got one).
		 */
		if ( -1 != typmod )
		{
			RegTypeImpl.Blessed t =
				(RegTypeImpl.Blessed)RECORD.modifier(typmod);

			if ( -1 == refcount ) // don't waste time on an ephemeral copy.
				return t.tupleDescriptor(); // just go get the real one.

			TupleDescriptor.Interned[] holder = t.m_tupDescHolder;
			if ( null != holder )
			{
				result = holder[0];
				assert null != result : "disagree whether RegType has desc";
				return result;
			}

			holder = new TupleDescriptor.Interned[1];
			/*
			 * The constructor assumes the reference count has already been
			 * incremented to account for the reference constructed here.
			 */
			s_getAndAddPlain.applyAsInt(td, 1);
			holder[0] = result = new Blessed(td, t);
			t.m_tupDescHolder = holder;
			return result;
		}

		/*
		 * Case 3: it's RECORD with no modifier, an ephemeral tuple type.
		 * Build an instance of Ephemeral unconditionally, defensively copying
		 * the descriptor if it isn't reference-counted (which we assert it
		 * isn't).
		 */
		assert -1 == refcount : "can any ephemeral TupleDesc be refcounted?";
		ByteBuffer copy = ByteBuffer.allocate(td.capacity()).put(td);
		return new Ephemeral(copy);
	}

	@Override
	public List<Attribute> attributes()
	{
		return this;
	}

	@Override
	public Attribute get(Simple name) throws SQLException
	{
		/*
		 * computeIfAbsent would be notationally simple here, but its guarantees
		 * aren't needed (this isn't a place where uniqueness needs to be
		 * enforced) and it's tricky to rule out that some name() call in the
		 * update could recursively end up here. So the longer check, compute,
		 * putIfAbsent is good enough.
		 */
		Attribute found = m_byName.get(name);
		if ( null != found )
			return found;

		for ( int i = m_byName.size() ; i < m_attrs.length ; ++ i )
		{
			Attribute a = m_attrs[i];
			Simple n = a.name();
			Attribute other = m_byName.putIfAbsent(n, a);
			assert null == other || found == other
				: "TupleDescriptor name cache";
			if ( ! name.equals(n) )
				continue;
			found = a;
			break;
		}

		if ( null == found )
			throw new SQLSyntaxErrorException(
				"no such column: " + name, "42703");

		return found;
	}

	@Override
	public Attribute sqlGet(int index)
	{
		return m_attrs[index - 1];
	}

	/*
	 * AbstractList implementation
	 */
	@Override
	public int size()
	{
		return m_attrs.length;
	}

	@Override
	public Attribute get(int index)
	{
		return m_attrs[index];
	}

	static class Cataloged extends TupleDescImpl implements Interned
	{
		private final RegClass m_relation;// using its SwitchPoint, keep it live

		Cataloged(ByteBuffer td, RegClassImpl c)
		{
			/*
			 * Invalidation of a Cataloged tuple descriptor happens with the
			 * SwitchPoint attached to the RegClass. Every Cataloged descriptor
			 * from the cache had better be reference-counted, so unconditional
			 * true is passed for useState.
			 */
			super(
				td, true,
				(o, i) -> CatalogObjectImpl.Factory.formAttribute(
					c.oid(), i, () -> new AttributeImpl.Cataloged(c))
			);

			m_relation = c; // we need it alive for its SwitchPoint
		}

		@Override
		public RegType rowType()
		{
			return m_relation.type();
		}
	}

	static class Blessed extends TupleDescImpl implements Interned
	{
		private final RegType m_rowType; // using its SwitchPoint, keep it live

		Blessed(ByteBuffer td, RegTypeImpl t)
		{
			/*
			 * A Blessed tuple descriptor has no associated RegClass, so we grab
			 * the SwitchPoint from the associated RegType, even though no
			 * invalidation event for it is ever expected. In fromByteBuffer,
			 * if we see a non-reference-counted descriptor, we grab one
			 * straight from the type cache instead. But sometimes, the one
			 * in PostgreSQL's type cache is non-reference counted, and that's
			 * ok, because that one will be good for the life of the process.
			 * So we do need to check, in this constructor, whether to pass true
			 * or false for useState. (Checking with getAndAddPlain(0) is a bit
			 * goofy, but it was already set up, matched to the field width,
			 * does the job.)
			 */
			super(
				td, -1 != s_getAndAddPlain.applyAsInt(td, 0),
				(o, i) -> new AttributeImpl.Transient(o, i)
			);

			m_rowType = t;
		}

		@Override
		public RegType rowType()
		{
			return m_rowType;
		}
	}

	static class Ephemeral extends TupleDescImpl
	implements TupleDescriptor.Ephemeral
	{
		private Ephemeral(ByteBuffer td)
		{
			super(
				td, false,
				(o, i) -> new AttributeImpl.Transient(o, i)
			);
		}

		@Override
		public RegType rowType()
		{
			return RECORD;
		}

		@Override
		public Interned intern()
		{
			return doInPG(() ->
			{
				TupleDescImpl sup = this; // its m_td is private

				ByteBuffer direct = ByteBuffer.allocateDirect(
					sup.m_td.capacity()).put(sup.m_td.rewind());

				int assigned = _assign_record_type_typmod(direct);

				/*
				 * That will have saved in the typcache an authoritative
				 * new copy of the descriptor. It will also have written
				 * the assigned modifier into the 'direct' copy of this
				 * descriptor, but this is still an Ephemeral instance,
				 * the wrong Java type. We need to return a new instance
				 * over the authoritative typcache copy.
				 */
				return RECORD.modifier(assigned).tupleDescriptor();
			});
		}
	}

	static class OfType extends TupleDescImpl
	implements TupleDescriptor.Ephemeral
	{
		OfType(RegType type)
		{
			super(type);
		}

		@Override
		public RegType rowType()
		{
			return RECORD;
		}

		@Override
		public Interned intern()
		{
			throw notyet();
		}
	}

	/**
	 * Based on {@code SingleFreeTupleDesc}, but really does
	 * {@code ReleaseTupleDesc}.
	 *<p>
	 * Decrements the reference count and, if it was 1 before decrementing,
	 * proceeds to the superclass method to free the descriptor.
	 */
	private static class State
	extends DualState.SingleFreeTupleDesc<TupleDescImpl>
	{
		private final IntSupplier m_getAndDecrPlain;

		private State(TupleDescImpl referent, ByteBuffer td)
		{
			super(referent, null, addressOf(td));
			/*
			 * The only reference to this non-readonly ByteBuffer retained here
			 * is what's bound into this getAndDecr for the reference count.
			 */
			m_getAndDecrPlain = () -> s_getAndAddPlain.applyAsInt(td, -1);
		}

		@Override
		protected void javaStateUnreachable(boolean nativeStateLive)
		{
			if ( nativeStateLive && 1 == m_getAndDecrPlain.getAsInt() )
				super.javaStateUnreachable(nativeStateLive);
		}

		private long address()
		{
			return guardedLong();
		}
	}

	/**
	 * Call the PostgreSQL {@code typcache} function of the same name, but
	 * return the assigned typmod rather than {@code void}.
	 */
	private static native int _assign_record_type_typmod(ByteBuffer bb);
}