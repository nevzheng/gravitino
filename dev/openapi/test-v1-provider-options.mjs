/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import Ajv2020 from "ajv/dist/2020.js";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const bundledSpecPath = resolve(scriptDirectory, "build/v1/openapi.json");
const providerOptionNames = Object.freeze([
  "icebergOptions",
  "hiveOptions",
  "clickhouseOptions",
  "mysqlOptions"
]);

/**
 * Reads the bundled V1 specification and extracts the schema that owns the
 * provider-options cardinality rule.
 *
 * @returns {object} the TableProviderOptionsConstraint JSON Schema.
 */
function loadProviderOptionsConstraint() {
  let specification;
  try {
    specification = JSON.parse(readFileSync(bundledSpecPath, "utf8"));
  } catch (error) {
    throw new Error(
      `Unable to read the bundled V1 OpenAPI specification at ${bundledSpecPath}. ` +
        "Run npm run bundle:v1 first.",
      { cause: error }
    );
  }

  const constraint =
    specification.components?.schemas?.TableProviderOptionsConstraint;
  assert.ok(
    constraint,
    "The bundled V1 specification must contain TableProviderOptionsConstraint."
  );
  assert.equal(
    constraint.type,
    "object",
    "TableProviderOptionsConstraint must validate object instances."
  );
  assert.ok(
    constraint.dependentSchemas,
    "TableProviderOptionsConstraint must use dependentSchemas for provider-option cardinality."
  );

  for (const optionName of providerOptionNames) {
    assert.ok(
      Object.hasOwn(constraint.dependentSchemas, optionName),
      `TableProviderOptionsConstraint must define a dependent schema for ${optionName}.`
    );
  }

  return constraint;
}

/**
 * Formats AJV failures so a contract regression is immediately actionable.
 *
 * @param {import("ajv").ErrorObject[] | null | undefined} errors AJV errors.
 * @returns {string} formatted validation failures.
 */
function formatErrors(errors) {
  return errors?.length ? errors.map((error) => JSON.stringify(error)).join("\n") : "none";
}

/**
 * Asserts whether an instance conforms to the provider-options constraint.
 *
 * @param {import("ajv").ValidateFunction} validate compiled AJV validator.
 * @param {object} instance test instance.
 * @param {boolean} expectedValid expected validation result.
 * @param {string} description case description.
 */
function assertValidation(validate, instance, expectedValid, description) {
  const valid = validate(instance);
  assert.equal(
    valid,
    expectedValid,
    `${description}: expected valid=${expectedValid}; AJV errors:\n${formatErrors(validate.errors)}`
  );
}

const providerOptionsConstraint = loadProviderOptionsConstraint();
const ajv = new Ajv2020({ allErrors: true, strict: true });
const validate = ajv.compile(providerOptionsConstraint);

assertValidation(validate, {}, true, "zero provider-option envelopes");

for (const optionName of providerOptionNames) {
  assertValidation(
    validate,
    { [optionName]: {} },
    true,
    `one provider-option envelope (${optionName})`
  );
}

for (let firstIndex = 0; firstIndex < providerOptionNames.length; firstIndex += 1) {
  for (
    let secondIndex = firstIndex + 1;
    secondIndex < providerOptionNames.length;
    secondIndex += 1
  ) {
    const firstOption = providerOptionNames[firstIndex];
    const secondOption = providerOptionNames[secondIndex];
    assertValidation(
      validate,
      { [firstOption]: {}, [secondOption]: {} },
      false,
      `two provider-option envelopes (${firstOption}, ${secondOption})`
    );
  }
}

console.log("V1 provider-options bundled-schema validation passed.");
