/*
 * Copyright 2021 Hazelcast Inc.
 *
 * Licensed under the Hazelcast Community License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://hazelcast.com/hazelcast-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.sql.impl.validate;

import com.hazelcast.jet.sql.impl.parse.SqlAlterJob;
import com.hazelcast.jet.sql.impl.parse.SqlCreateJob;
import com.hazelcast.jet.sql.impl.parse.SqlCreateSnapshot;
import com.hazelcast.jet.sql.impl.parse.SqlDropJob;
import com.hazelcast.jet.sql.impl.parse.SqlDropSnapshot;
import com.hazelcast.jet.sql.impl.parse.SqlOption;
import com.hazelcast.jet.sql.impl.parse.SqlShowStatement;
import com.hazelcast.jet.sql.impl.schema.HazelcastDynamicTableFunction;
import com.hazelcast.jet.sql.impl.validate.types.HazelcastTypeUtils;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.runtime.Resources.ExInst;
import org.apache.calcite.sql.JoinConditionType;
import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.SqlBasicTypeNameSpec;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlSelectKeyword;
import org.apache.calcite.sql.SqlUserDefinedTypeNameSpec;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.fun.SqlTrimFunction;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.calcite.sql.validate.SqlValidatorException;

import java.util.HashSet;
import java.util.Set;

import static com.hazelcast.jet.sql.impl.validate.ValidatorResource.RESOURCE;

/**
 * Visitor that throws exceptions for unsupported SQL features.
 */
@SuppressWarnings("checkstyle:ExecutableStatementCount")
public final class UnsupportedOperationVisitor extends SqlBasicVisitor<Void> {

    /**
     * A set of {@link SqlKind} values that are supported without any additional validation.
     */
    private static final Set<SqlKind> SUPPORTED_KINDS;

    /**
     * A set of supported operators for functions.
     */
    private static final Set<SqlOperator> SUPPORTED_OPERATORS;

    static {
        // We define all supported features explicitly instead of getting them from predefined sets of SqlKind class.
        // This is needed to ensure that we do not miss any unsupported features when something is added to a new version
        // of Apache Calcite.
        SUPPORTED_KINDS = new HashSet<>();

        // Predicates
        SUPPORTED_KINDS.add(SqlKind.AND);
        SUPPORTED_KINDS.add(SqlKind.OR);
        SUPPORTED_KINDS.add(SqlKind.NOT);
        SUPPORTED_KINDS.add(SqlKind.IN);
        SUPPORTED_KINDS.add(SqlKind.NOT_IN);
        SUPPORTED_KINDS.add(SqlKind.BETWEEN);

        // Arithmetics
        SUPPORTED_KINDS.add(SqlKind.PLUS);
        SUPPORTED_KINDS.add(SqlKind.MINUS);
        SUPPORTED_KINDS.add(SqlKind.TIMES);
        SUPPORTED_KINDS.add(SqlKind.DIVIDE);
        SUPPORTED_KINDS.add(SqlKind.MOD);
        SUPPORTED_KINDS.add(SqlKind.MINUS_PREFIX);
        SUPPORTED_KINDS.add(SqlKind.PLUS_PREFIX);

        // "IS" predicates
        SUPPORTED_KINDS.add(SqlKind.IS_TRUE);
        SUPPORTED_KINDS.add(SqlKind.IS_NOT_TRUE);
        SUPPORTED_KINDS.add(SqlKind.IS_FALSE);
        SUPPORTED_KINDS.add(SqlKind.IS_NOT_FALSE);
        SUPPORTED_KINDS.add(SqlKind.IS_NULL);
        SUPPORTED_KINDS.add(SqlKind.IS_NOT_NULL);

        // Comparison predicates
        SUPPORTED_KINDS.add(SqlKind.EQUALS);
        SUPPORTED_KINDS.add(SqlKind.NOT_EQUALS);
        SUPPORTED_KINDS.add(SqlKind.LESS_THAN);
        SUPPORTED_KINDS.add(SqlKind.GREATER_THAN);
        SUPPORTED_KINDS.add(SqlKind.GREATER_THAN_OR_EQUAL);
        SUPPORTED_KINDS.add(SqlKind.LESS_THAN_OR_EQUAL);

        // Miscellaneous
        SUPPORTED_KINDS.add(SqlKind.AS);
        SUPPORTED_KINDS.add(SqlKind.CAST);
        SUPPORTED_KINDS.add(SqlKind.CEIL);
        SUPPORTED_KINDS.add(SqlKind.FLOOR);
        SUPPORTED_KINDS.add(SqlKind.LIKE);
        SUPPORTED_KINDS.add(SqlKind.TRIM);

        SUPPORTED_KINDS.add(SqlKind.CASE);
        SUPPORTED_KINDS.add(SqlKind.NULLIF);
        SUPPORTED_KINDS.add(SqlKind.COALESCE);
        SUPPORTED_KINDS.add(SqlKind.UNION);

        // Aggregations
        SUPPORTED_KINDS.add(SqlKind.COUNT);
        SUPPORTED_KINDS.add(SqlKind.MIN);
        SUPPORTED_KINDS.add(SqlKind.MAX);
        SUPPORTED_KINDS.add(SqlKind.SUM);
        SUPPORTED_KINDS.add(SqlKind.AVG);

        // DDL & DML
        SUPPORTED_KINDS.add(SqlKind.CREATE_TABLE);
        SUPPORTED_KINDS.add(SqlKind.DROP_TABLE);
        SUPPORTED_KINDS.add(SqlKind.COLUMN_DECL);

        SUPPORTED_KINDS.add(SqlKind.ROW);
        SUPPORTED_KINDS.add(SqlKind.VALUES);
        SUPPORTED_KINDS.add(SqlKind.INSERT);

        // Table functions
        SUPPORTED_KINDS.add(SqlKind.COLLECTION_TABLE);
        SUPPORTED_KINDS.add(SqlKind.ARGUMENT_ASSIGNMENT);

        // Ordering
        SUPPORTED_KINDS.add(SqlKind.DESCENDING);

        // Supported operators
        SUPPORTED_OPERATORS = new HashSet<>();

        // Math
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.POWER);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.SQUARE);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.SQRT);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.CBRT);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.COS);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.SIN);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.TAN);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.COT);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.ACOS);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.ASIN);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.ATAN);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.ATAN2);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.EXP);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.LN);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.LOG10);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.RAND);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.ABS);
        SUPPORTED_OPERATORS.add(SqlStdOperatorTable.PI);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.SIGN);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.DEGREES);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.RADIANS);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.ROUND);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.TRUNCATE);

        // Strings
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.ASCII);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.INITCAP);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.CHAR_LENGTH);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.CHARACTER_LENGTH);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.LENGTH);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.LOWER);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.UPPER);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.CONCAT);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.CONCAT_WS);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.SUBSTRING);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.LTRIM);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.RTRIM);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.BTRIM);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.REPLACE);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.POSITION);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.NOT_LIKE);

        // Datetime
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.EXTRACT);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.TO_TIMESTAMP_TZ);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.TO_EPOCH_MILLIS);

        // Extensions
        SUPPORTED_OPERATORS.add(SqlOption.OPERATOR);
        SUPPORTED_OPERATORS.add(SqlShowStatement.SHOW_MAPPINGS);
        SUPPORTED_OPERATORS.add(SqlShowStatement.SHOW_JOBS);

        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.GENERATE_SERIES);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.GENERATE_STREAM);

        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.CSV_FILE);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.JSON_FLAT_FILE);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.AVRO_FILE);
        SUPPORTED_OPERATORS.add(HazelcastSqlOperatorTable.PARQUET_FILE);
    }

    // The top level select is used to filter out nested selects with FETCH/OFFSET
    private SqlSelect topLevelSelect;

    @Override
    public Void visit(SqlCall call) {
        // remove the branch when MAP/MAP_VALUE_CONSTRUCTOR gets proper support
        if (!(call.getOperator() instanceof HazelcastDynamicTableFunction)) {
            processCall(call);

            call.getOperator().acceptCall(this, call);
        }

        return null;
    }

    @Override
    public Void visit(SqlDataTypeSpec type) {
        if (type.getTypeNameSpec() instanceof SqlUserDefinedTypeNameSpec) {
            SqlIdentifier typeName = type.getTypeName();

            if (HazelcastTypeUtils.isObjectIdentifier(typeName)) {
                return null;
            }
        }

        if (!(type.getTypeNameSpec() instanceof SqlBasicTypeNameSpec)) {
            throw error(type, RESOURCE.error("Complex type specifications are not supported"));
        }

        SqlTypeName typeName = SqlTypeName.get(type.getTypeName().getSimple());
        switch (typeName) {
            case BOOLEAN:
            case TINYINT:
            case SMALLINT:
            case INTEGER:
            case BIGINT:
            case DECIMAL:
            case REAL:
            case DOUBLE:
            case VARCHAR:
            case DATE:
            case TIME:
            case TIMESTAMP:
            case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
            case NULL:
                return null;

            case CHAR:
                // char should be not accessible by users, we have only VARCHAR
            case ANY:
                // visible to users as OBJECT
            default:
                throw error(type, ValidatorResource.RESOURCE.notSupported(typeName.getName()));
        }
    }

    @Override
    public Void visit(SqlLiteral literal) {
        SqlTypeName typeName = literal.getTypeName();

        if (HazelcastTypeUtils.isIntervalType(typeName)) {
            return null;
        }

        switch (typeName) {
            case BOOLEAN:
            case TINYINT:
            case SMALLINT:
            case INTEGER:
            case BIGINT:
            case DECIMAL:
            case REAL:
            case DOUBLE:
            case VARCHAR:
                // CHAR is present here to support string literals: Calcite expects
                // string literals to be of CHAR type, not VARCHAR. Validated type
                // of string literals is still VARCHAR in HazelcastSqlValidator.
            case CHAR:
            case TIMESTAMP:
            case TIMESTAMP_WITH_LOCAL_TIME_ZONE:
            case DATE:
            case TIME:
            case ANY:
            case NULL:
                return null;

            case SYMBOL:
                Object symbolValue = literal.getValue();

                if (symbolValue instanceof SqlTrimFunction.Flag) {
                    return null;
                }
                // `SELECT ALL` is the opposite of `SELECT DISTINCT` and it's the default if neither is used, we allow it
                if (symbolValue == SqlSelectKeyword.DISTINCT || symbolValue == SqlSelectKeyword.ALL) {
                    return null;
                }

                if (symbolValue == JoinType.INNER
                        || symbolValue == JoinType.COMMA
                        || symbolValue == JoinType.CROSS
                        || symbolValue == JoinType.LEFT
                        || symbolValue == JoinType.RIGHT
                ) {
                    return null;
                }
                if (symbolValue == JoinConditionType.ON
                        || symbolValue == JoinConditionType.NONE
                        || symbolValue == JoinConditionType.USING
                ) {
                    return null;
                }

                throw error(literal, RESOURCE.error(symbolValue + " literal is not supported"));

            default:
                throw error(literal, RESOURCE.error(typeName + " literals are not supported"));
        }
    }

    private void processCall(SqlCall call) {
        SqlKind kind = call.getKind();

        if (SUPPORTED_KINDS.contains(kind)) {
            return;
        }

        switch (kind) {
            case SELECT:
                processSelect((SqlSelect) call);
                break;

            case UPDATE:
            case DELETE:
                break;

            case JOIN:
                processJoin((SqlJoin) call);
                break;

            case OTHER:
            case OTHER_FUNCTION:
            case EXTRACT:
            case POSITION:
                processOther(call);
                break;

            case OTHER_DDL:
                processOtherDdl(call);
                break;

            default:
                throw unsupported(call, kind);
        }
    }

    private void processSelect(SqlSelect select) {
        if (topLevelSelect == null) {
            topLevelSelect = select;
        } else {
            // Check for nested fetch offset
            if (select.getFetch() != null || select.getOffset() != null) {
                throw error(select, "FETCH/OFFSET is only supported for the top-level SELECT");
            }
        }
    }

    private void processJoin(SqlJoin join) {
        JoinType joinType = join.getJoinType();

        if (joinType != JoinType.INNER
                && joinType != JoinType.COMMA
                && joinType != JoinType.CROSS
                && joinType != JoinType.LEFT
                && joinType != JoinType.RIGHT
        ) {
            throw unsupported(join, joinType.name() + " join");
        }
    }

    private void processOther(SqlCall call) {
        SqlOperator operator = call.getOperator();

        if (SUPPORTED_OPERATORS.contains(operator)) {
            return;
        }

        throw unsupported(call, operator.getName());
    }

    private void processOtherDdl(SqlCall call) {
        if (!(call instanceof SqlCreateJob)
                && !(call instanceof SqlDropJob)
                && !(call instanceof SqlAlterJob)
                && !(call instanceof SqlCreateSnapshot)
                && !(call instanceof SqlDropSnapshot)
        ) {
            throw unsupported(call, "OTHER DDL class (" + call.getClass().getSimpleName() + ")");
        }
    }

    private CalciteContextException unsupported(SqlNode node, SqlKind kind) {
        return unsupported(node, kind.sql.replace('_', ' '));
    }

    private CalciteContextException unsupported(SqlNode node, String name) {
        return error(node, ValidatorResource.RESOURCE.notSupported(name));
    }

    private static CalciteContextException error(SqlNode node, ExInst<SqlValidatorException> error) {
        return SqlUtil.newContextException(node.getParserPosition(), error);
    }

    public static CalciteContextException error(SqlNode node, String name) {
        return error(node, RESOURCE.error(name));
    }
}
