/*
 * This file contains software that has been made available under
 * The Mozilla Public License 1.1. Use and distribution hereof are
 * subject to the restrictions set forth therein.
 *
 * Copyright (c) 2003 TADA AB - Taby Sweden
 * All Rights Reserved
 */
#include <postgres.h>
#include <executor/spi.h>

#include "pljava/Exception.h"
#include "pljava/type/Type_priv.h"
#include "pljava/type/String.h"
#include "pljava/type/TupleDesc.h"
#include "pljava/type/Tuple.h"
#include "pljava/type/Relation.h"
#include "pljava/type/Relation_JNI.h"

static Type      s_Relation;
static TypeClass s_RelationClass;
static jclass    s_Relation_class;
static jmethodID s_Relation_init;

/*
 * org.postgresql.pljava.Relation type.
 */
jobject Relation_create(JNIEnv* env, Relation td)
{
	if(td == 0)
		return 0;

	jobject jtd = NativeStruct_obtain(env, td);
	if(jtd == 0)
	{
		jtd = (*env)->NewObject(env, s_Relation_class, s_Relation_init);
		NativeStruct_init(env, jtd, td);
	}
	return jtd;
}

static jvalue _Relation_coerceDatum(Type self, JNIEnv* env, Datum arg)
{
	jvalue result;
	result.l = Relation_create(env, (Relation)DatumGetPointer(arg));
	return result;
}

static Type Relation_obtain(Oid typeId)
{
	return s_Relation;
}

/* Make this datatype available to the postgres system.
 */
extern Datum Relation_initialize(PG_FUNCTION_ARGS);
PG_FUNCTION_INFO_V1(Relation_initialize);
Datum Relation_initialize(PG_FUNCTION_ARGS)
{
	JNIEnv* env = (JNIEnv*)PG_GETARG_POINTER(0);

	s_Relation_class = (*env)->NewGlobalRef(
				env, PgObject_getJavaClass(env, "org/postgresql/pljava/Relation"));

	s_Relation_init = PgObject_getJavaMethod(
				env, s_Relation_class, "<init>", "()V");

	s_RelationClass = NativeStructClass_alloc("type.Relation");
	s_RelationClass->JNISignature   = "Lorg/postgresql/pljava/Relation;";
	s_RelationClass->javaTypeName   = "org.postgresql.pljava.Relation";
	s_RelationClass->coerceDatum    = _Relation_coerceDatum;
	s_Relation = TypeClass_allocInstance(s_RelationClass);

	Type_registerJavaType("org.postgresql.pljava.Relation", Relation_obtain);
	PG_RETURN_VOID();
}

/*
 * Class:     org_postgresql_pljava_Relation
 * Method:    getName
 * Signature: ()Ljava/lang/String;
 */
JNIEXPORT jstring JNICALL
Java_org_postgresql_pljava_Relation_getName(JNIEnv* env, jobject _this)
{
	Relation self = (Relation)NativeStruct_getStruct(env, _this);
	if(self == 0)
		return 0;
	
	char* relName = SPI_getrelname(self);
	jstring ret = String_createJavaStringFromNTS(env, relName);
	pfree(relName);
	return ret;
}
/*
 * Class:     org_postgresql_pljava_Relation
 * Method:    getTupleDesc
 * Signature: ()Lorg/postgresql/pljava/TupleDesc;
 */
JNIEXPORT jobject JNICALL
Java_org_postgresql_pljava_Relation_getTupleDesc(JNIEnv* env, jobject _this)
{
	Relation self = (Relation)NativeStruct_getStruct(env, _this);
	if(self == 0)
		return 0;

	return TupleDesc_create(env, self->rd_att);
}

/*
 * Class:     org_postgresql_pljava_Relation
 * Method:    modifyTuple
 * Signature: (Lorg/postgresql/pljava/Tuple;[I[Ljava/lang/Object;)Lorg/postgresql/pljava/Tuple;
 */
JNIEXPORT jobject JNICALL
Java_org_postgresql_pljava_Relation_modifyTuple(JNIEnv* env, jobject _this, jobject _tuple, jintArray _indexes, jobjectArray _values)
{
	Relation self = (Relation)NativeStruct_getStruct(env, _this);
	if(self == 0)
		return 0;

	HeapTuple tuple = (HeapTuple)NativeStruct_getStruct(env, _tuple);
	if(tuple == 0)
		return 0;

	TupleDesc tupleDesc = self->rd_att;

	jint   count  = (*env)->GetArrayLength(env, _indexes);
	Datum* values = (Datum*)palloc(count * sizeof(Datum));
	char*  nulls  = 0;

	jint* javaIdxs = (*env)->GetIntArrayElements(env, _indexes, 0);

	int* indexes;
	if(sizeof(int) == sizeof(jint))	/* compiler will optimize this */
		indexes = (int*)javaIdxs;
	else
		indexes = (int*)palloc(count * sizeof(int));

	jint idx;
	for(idx = 0; idx < count; ++idx)
	{
		int attIndex;
		if(sizeof(int) == sizeof(jint))	/* compiler will optimize this */
			attIndex = indexes[idx];
		else
		{
			attIndex = (int)javaIdxs[idx];
			indexes[idx] = attIndex;
		}

		Oid typeId = SPI_gettypeid(tupleDesc, attIndex);
		if(!OidIsValid(typeId))
		{
			Exception_throw(env,
				ERRCODE_INVALID_DESCRIPTOR_INDEX,
				"Invalid attribute index \"%d\"", attIndex);
			return 0L;	/* Exception */
		}

		Type type = Type_fromOid(typeId);
		jobject value = (*env)->GetObjectArrayElement(env, _values, idx);
		if(value != 0)
			values[idx] = type->m_class->coerceObject(type, env, value);
		else
		{
			if(nulls == 0)
			{
				nulls = (char*)palloc(count+1);
				memset(nulls, count, ' ');	/* all values non-null initially */
				nulls[count] = 0;
			}
			nulls[idx] = 'n';
			values[idx] = 0;
		}
	}

	tuple = SPI_modifytuple(self, tuple, count, indexes, values, nulls);
	(*env)->ReleaseIntArrayElements(env, _indexes, javaIdxs, JNI_ABORT);

	if(sizeof(int) != sizeof(jint))	/* compiler will optimize this */
		pfree(indexes);

	pfree(values);
	if(nulls != 0)
		pfree(nulls);

	if(tuple == 0)
	{
		Exception_throwSPI(env, "modifytuple");
		return 0L;	/* Exception */
	}

	return Tuple_create(env, tuple);
}
