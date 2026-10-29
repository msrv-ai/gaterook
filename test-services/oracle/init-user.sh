#!/bin/bash
# Create the connector's schema in the existing PDB with only the permissions required by its integration tests.
set -euo pipefail

[[ "${GW_ORACLE_SERVICE}" =~ ^[A-Z][A-Z0-9_]*$ ]]
[[ "${GW_ORACLE_USER}" =~ ^[A-Z][A-Z0-9_]*$ ]]
[[ "${GW_ORACLE_PASSWORD}" =~ ^[A-Za-z0-9_-]+$ ]]

sqlplus -L -s / as sysdba <<SQL
WHENEVER OSERROR EXIT FAILURE
WHENEVER SQLERROR EXIT FAILURE
SET DEFINE OFF
ALTER SESSION SET CONTAINER = ${GW_ORACLE_SERVICE};
CREATE USER ${GW_ORACLE_USER} IDENTIFIED BY "${GW_ORACLE_PASSWORD}"
    DEFAULT TABLESPACE USERS QUOTA 20M ON USERS;
GRANT CREATE SESSION, CREATE TABLE TO ${GW_ORACLE_USER};
EXIT SUCCESS
SQL
