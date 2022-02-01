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

import java.lang.invoke.MethodHandle;
import static java.lang.invoke.MethodHandles.lookup;
import java.lang.invoke.SwitchPoint;

import java.sql.SQLException;

import java.util.function.UnaryOperator;

import org.postgresql.pljava.internal.SwitchPointCache.Builder;

import org.postgresql.pljava.model.*;

import org.postgresql.pljava.pg.CatalogObjectImpl.*;
import static org.postgresql.pljava.pg.ModelConstants.TSDICTOID; // syscache

import static org.postgresql.pljava.pg.adt.NameAdapter.SIMPLE_INSTANCE;
import static org.postgresql.pljava.pg.adt.OidAdapter.REGNAMESPACE_INSTANCE;
import static org.postgresql.pljava.pg.adt.OidAdapter.REGROLE_INSTANCE;

import org.postgresql.pljava.sqlgen.Lexicals.Identifier.Simple;
import org.postgresql.pljava.sqlgen.Lexicals.Identifier.Unqualified;

class RegDictionaryImpl extends Addressed<RegDictionary>
implements Nonshared<RegDictionary>, Namespaced<Simple>, Owned, RegDictionary
{
	private static UnaryOperator<MethodHandle[]> s_initializer;

	/* Implementation of Addressed */

	@Override
	public RegClass.Known<RegDictionary> classId()
	{
		return CLASSID;
	}

	@Override
	int cacheId()
	{
		return TSDICTOID;
	}

	/* Implementation of Named, Namespaced, Owned */

	private static Simple name(RegDictionaryImpl o) throws SQLException
	{
		TupleTableSlot t = o.cacheTuple();
		return t.get(t.descriptor().get("dictname"), SIMPLE_INSTANCE);
	}

	private static RegNamespace namespace(RegDictionaryImpl o)
	throws SQLException
	{
		TupleTableSlot t = o.cacheTuple();
		return
			t.get(t.descriptor().get("dictnamespace"), REGNAMESPACE_INSTANCE);
	}

	private static RegRole owner(RegDictionaryImpl o) throws SQLException
	{
		TupleTableSlot t = o.cacheTuple();
		return t.get(t.descriptor().get("dictowner"), REGROLE_INSTANCE);
	}

	/* Implementation of RegDictionary */

	/**
	 * Merely passes the supplied slots array to the superclass constructor; all
	 * initialization of the slots will be the responsibility of the subclass.
	 */
	RegDictionaryImpl()
	{
		super(s_initializer.apply(new MethodHandle[NSLOTS]));
	}

	static
	{
		s_initializer =
			new Builder<>(RegDictionaryImpl.class)
			.withLookup(lookup())
			.withSwitchPoint(o -> s_globalPoint[0])
			.withSlots(o -> o.m_slots)
			.withCandidates(RegDictionaryImpl.class.getDeclaredMethods())

			.withReceiverType(CatalogObjectImpl.Named.class)
			.withReturnType(Unqualified.class)
			.withDependent(      "name", SLOT_NAME)
			.withReturnType(null)
			.withReceiverType(CatalogObjectImpl.Namespaced.class)
			.withDependent( "namespace", SLOT_NAMESPACE)
			.withReceiverType(CatalogObjectImpl.Owned.class)
			.withDependent(     "owner", SLOT_OWNER)

			.build()
			.compose(CatalogObjectImpl.Addressed.s_initializer)::apply;
	}
}