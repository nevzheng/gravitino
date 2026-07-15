--
-- Licensed to the Apache Software Foundation (ASF) under one
-- or more contributor license agreements.  See the NOTICE file
-- distributed with this work for additional information
-- regarding copyright ownership.  The ASF licenses this file
-- to you under the Apache License, Version 2.0 (the
-- "License"); you may not use this file except in compliance
-- with the License.  You may obtain a copy of the License at
--
--  http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing,
-- software distributed under the License is distributed on an
-- "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
-- KIND, either express or implied.  See the License for the
-- specific language governing permissions and limitations
-- under the License.
--

USE lakehouse.customer_data;

CREATE TABLE encrypted_customer_records (
  id BIGINT,
  marker STRING
)
TBLPROPERTIES (
  'format-version' = '3',
  'encryption.key-id' = 'customer-pii-v1'
);

INSERT INTO encrypted_customer_records
VALUES (1, 'governed-encryption-poc-marker');

SELECT 'APPROVED_SPARK_WRITE_OK' AS proof, COUNT(*) AS rows_written
FROM encrypted_customer_records;
