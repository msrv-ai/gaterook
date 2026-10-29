#!/bin/bash
# Check the same TCP service and schema credentials used by the connector, including a successful query.
set -euo pipefail

result=$(sqlplus -L -s /nolog <<SQL
WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT FAILURE
SET DEFINE OFF
SET HEADING OFF
SET FEEDBACK OFF
SET PAGESIZE 0
CONNECT ${GW_ORACLE_USER}/"${GW_ORACLE_PASSWORD}"@//127.0.0.1:1521/${GW_ORACLE_SERVICE}
SELECT 'GATEROOK_ORACLE_READY' FROM DUAL;
EXIT SUCCESS
SQL
)
[[ "${result}" == *GATEROOK_ORACLE_READY* ]]
