package com.lmreader.core.workflow

import com.lmreader.core.model.*
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Test

class WorkflowJsonSchemaTest {
    @Test fun selectedTypesBecomeExactRequiredFieldsWithNestedListTypes() {
        val result=Json.parseToJsonElement(WorkflowJsonSchema.responseFormat(WorkflowType.list(WorkflowType.PAGE_RECORD))!!).jsonObject
        assertEquals("json_schema", result.getValue("type").jsonPrimitive.content)
        val definition=result.getValue("json_schema").jsonObject
        assertTrue(definition.getValue("strict").jsonPrimitive.boolean)
        val schema=definition.getValue("schema").jsonObject
        assertEquals("array",schema.getValue("type").jsonPrimitive.content)
        val record=schema.getValue("items").jsonObject
        assertEquals(WorkflowType.PAGE_RECORD.fields.keys,record.getValue("properties").jsonObject.keys)
        assertEquals(WorkflowType.PAGE_RECORD.fields.keys,record.getValue("required").jsonArray.map {it.jsonPrimitive.content}.toSet())
        assertFalse(record.getValue("additionalProperties").jsonPrimitive.boolean)
        assertEquals("number",record.getValue("properties").jsonObject.getValue("pageNumber").jsonObject.getValue("type").jsonPrimitive.content)
    }
    @Test fun rawTextRemainsUnconstrainedAndApiCannotConstructImages() {
        assertNull(WorkflowJsonSchema.responseFormat(WorkflowType.TEXT))
        assertFails {WorkflowJsonSchema.responseFormat(WorkflowType.list(WorkflowType.IMAGE))}
    }
}
