#!/bin/bash
# Reserve room for Oracle's processes within the fixture's 2 GiB limit before the first database startup.
set -euo pipefail

if [[ ! -d "${ORACLE_BASE}/oradata/dbconfig/${ORACLE_SID}" ]]; then
    spfile="${ORACLE_BASE_CONFIG}/dbs/spfile${ORACLE_SID}.ora"
    pfile=$(mktemp /tmp/gaterook-oracle.XXXXXX)
    trap 'rm -f "${pfile}"' EXIT

    sqlplus -L -s / as sysdba <<SQL
WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT FAILURE
CREATE PFILE = '${pfile}' FROM SPFILE = '${spfile}';
EXIT SUCCESS
SQL

    # Discard the image's derived pool sizes so Oracle can recalculate them for the smaller test workload.
    sed -i '/^[^.]*\.__/d; /^[^.]*\.sga_target=/d; /^[^.]*\.pga_aggregate_target=/d' "${pfile}"
    printf '\n*.sga_target=768M\n*.pga_aggregate_target=256M\n' >> "${pfile}"

    sqlplus -L -s / as sysdba <<SQL
WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT FAILURE
CREATE SPFILE = '${spfile}' FROM PFILE = '${pfile}';
EXIT SUCCESS
SQL

    rm -f "${pfile}"
    trap - EXIT
fi

exec container-entrypoint.sh "$@"
