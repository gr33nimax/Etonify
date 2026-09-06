package io.hydrabox.core.config

import io.hydrabox.core.subscription.CatalogOutbound
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two subscriptions naming a server the same way, and a subscription naming a server what this
 * generator calls its own groups. Both were configurations the core refuses or silently turns
 * into a direct route, and both are ordinary things for a provider to do.
 */
class OutboundTagsTest {
    @Test
    fun `a selector default follows its renamed member`() {
        val group = outbound("pick", scope = "two", type = "selector", members = listOf("edge"), selectable = false)
        val withDefault = group.copy(json = kotlinx.serialization.json.JsonObject(group.json + ("default" to JsonPrimitive("edge"))))
        val normalized = OutboundTags.normalize(listOf(outbound("edge", scope = "one"), outbound("edge", scope = "two"), withDefault))
        assertEquals("edge@two", normalized.outbounds.last().json["default"]!!.jsonPrimitive.content)
    }

    private fun outbound(
        tag: String,
        scope: String = "",
        detour: String? = null,
        members: List<String>? = null,
        type: String = "vless",
        selectable: Boolean = true,
    ) = CatalogOutbound(
        tag = tag,
        type = type,
        scope = scope,
        selectable = selectable,
        json = buildJsonObject {
            put("type", type)
            put("tag", tag)
            detour?.let { put("detour", it) }
            members?.let { list -> putJsonArray("outbounds") { list.forEach { add(JsonPrimitive(it)) } } }
        },
    )

    @Test
    fun `two sources naming one server keep one identity each`() {
        val normalized = OutboundTags.normalize(
            listOf(outbound("same", scope = "one"), outbound("same", scope = "two")),
        ).outbounds
        assertEquals(listOf("same", "same@two"), normalized.map(CatalogOutbound::tag))
        assertEquals(2, normalized.map(CatalogOutbound::tag).toSet().size)
        // The provider's own name survives for the screen that shows it.
        assertEquals("same", normalized[1].label)
        assertEquals("same", normalized[1].originTag)
    }

    @Test
    fun `a reference is rewritten inside its own source and nowhere else`() {
        val normalized = OutboundTags.normalize(
            listOf(
                outbound("edge", scope = "one"),
                outbound("front", scope = "one", detour = "edge"),
                outbound("edge", scope = "two"),
                outbound("front", scope = "two", detour = "edge"),
            ),
        ).outbounds
        assertEquals(listOf("edge", "front", "edge@two", "front@two"), normalized.map(CatalogOutbound::tag))
        assertEquals("edge", normalized[1].json["detour"]?.jsonPrimitive?.content)
        assertEquals("edge@two", normalized[3].json["detour"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a group member is renamed with the member it names`() {
        val normalized = OutboundTags.normalize(
            listOf(
                outbound("edge", scope = "one"),
                outbound("edge", scope = "two"),
                outbound("pick", scope = "two", type = "selector", members = listOf("edge"), selectable = false),
            ),
        ).outbounds
        assertEquals(
            listOf("edge@two"),
            normalized[2].json["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `the generator's own tags are never taken by a subscription`() {
        val normalized = OutboundTags.normalize(listOf(outbound("auto", scope = "one"))).outbounds
        assertEquals("auto@one", normalized.single().tag)
    }

    @Test
    fun `a rename is followable only when nothing answers to the old name`() {
        val reserved = OutboundTags.normalize(listOf(outbound("select", scope = "one")))
        assertEquals("select@one", reserved.renames["select"])
        // The first holder kept the name, so following it would point at somebody else's server.
        val collision = OutboundTags.normalize(
            listOf(outbound("same", scope = "one"), outbound("same", scope = "two")),
        )
        assertNull(collision.renames["same"])
    }

    @Test
    fun `normalising twice changes nothing`() {
        val once = OutboundTags.normalize(
            listOf(outbound("auto", scope = "one"), outbound("auto", scope = "two")),
        ).outbounds
        assertEquals(once, OutboundTags.normalize(once).outbounds)
    }

    @Test
    fun `a server named like a group stays a proxy instead of becoming a direct route`() {
        val built = TunnelConfigGenerator.build(
            TunnelInput(outbounds = listOf(outbound("auto", scope = "one")), selectedTag = "auto"),
        )
        assertNotEquals("direct", built["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
        val tags = built["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue(tags.contains("auto@one"), "the supplied server must still be in the configuration")
        assertTrue(tags.contains(AUTO_TAG), "the generator's own group must still be there")
    }

    @Test
    fun `a subscription whose only server is a VK transport named like a group still works`() {
        val call = CatalogOutbound(
            tag = AUTO_TAG,
            type = "call",
            scope = "one",
            json = buildJsonObject { put("type", "call"); put("tag", AUTO_TAG) },
        )
        val built = TunnelConfigGenerator.build(TunnelInput(outbounds = listOf(call), selectedTag = AUTO_TAG))
        val tags = built["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue(tags.contains("auto@one"))
        assertEquals(SELECTOR_TAG, built["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
    }
}
