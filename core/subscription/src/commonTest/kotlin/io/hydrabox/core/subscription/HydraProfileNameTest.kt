package io.hydrabox.core.subscription

import kotlin.test.Test
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals

/**
 * The Hydra contract, on the one point a person sees: a profile carries the name the provider
 * wrote, and the entrypoint tag underneath it is ours. The server named "обход БС" on the
 * provider's side was shown as `call-vk-out` because only the tag was read.
 */
class HydraProfileNameTest {
    private val document = """
        {
          "api_version": "hydra.io/subscription/v2",
          "kind": "Subscription",
          "display": { "name": { "default": "HYDRA", "ru": "ГИДРА" } },
          "default_profile": "bypass",
          "profiles": [
            {
              "id": "bypass",
              "resource": "main",
              "name": { "default": "обход БС" },
              "entrypoint": { "section": "outbounds", "tag": "call-vk-out" }
            },
            {
              "id": "plain",
              "resource": "main",
              "name": "Tokyo",
              "entrypoint": { "section": "outbounds", "tag": "vless-tokyo" }
            }
          ],
          "resources": [
            {
              "id": "main",
              "document": {
                "outbounds": [
                  { "type": "call", "tag": "call-vk-out", "mode": "vk_parasite" },
                  { "type": "vless", "tag": "vless-tokyo", "server": "example" },
                  { "type": "direct", "tag": "helper" }
                ]
              }
            }
          ]
        }
    """.trimIndent()

    @Test fun `a profile name is what the server list shows, and the tag stays the identity`() {
        val catalog = OutboundCatalogParser.parse(document)
        val entrypoint = catalog.outbounds.single { it.tag == "call-vk-out" }
        assertEquals("обход БС", entrypoint.label)
        assertEquals("Tokyo", catalog.outbounds.single { it.tag == "vless-tokyo" }.label)
        // Everything is embedded so detours keep resolving, but only what a profile points at
        // is offered as a choice, and a helper outbound has no name to show.
        assertEquals(listOf("call-vk-out", "vless-tokyo"), catalog.selectable.map { it.tag })
        assertEquals(null, catalog.outbounds.single { it.tag == "helper" }.label)
        assertEquals("call-vk-out", catalog.defaultTag)
    }
    private val twoResources = """
        {
          "api_version": "hydra.io/subscription/v2",
          "profiles": [
            { "id": "a", "resource": "one", "name": "Первый", "entrypoint": { "section": "outbounds", "tag": "exit" } },
            { "id": "b", "resource": "two", "name": "Второй", "entrypoint": { "section": "outbounds", "tag": "exit" } }
          ],
          "resources": [
            {
              "id": "one",
              "document": {
                "outbounds": [
                  { "type": "vless", "tag": "exit", "server": "one.example", "detour": "hop" },
                  { "type": "shadowsocks", "tag": "hop", "server": "hop.example" }
                ]
              }
            },
            {
              "id": "two",
              "document": {
                "outbounds": [ { "type": "vless", "tag": "exit", "server": "two.example" } ]
              }
            }
          ]
        }
    """.trimIndent()

    @Test fun `two resources may name a server the same way and neither is lost`() {
        val catalog = OutboundCatalogParser.parse(twoResources)
        // The second one is renamed rather than dropped: one configuration cannot hold two
        // outbounds with the same tag, and the core refuses the whole document if it does.
        assertEquals(listOf("exit", "hop", "exit@two"), catalog.outbounds.map { it.tag })
        assertEquals(listOf("Первый", "Второй"), catalog.selectable.map { it.label })
        // A detour still points at a sibling that exists.
        assertEquals(
            "hop",
            catalog.outbounds.single { it.tag == "exit" }.json["detour"]!!.jsonPrimitive.content,
        )
        // And a profile in one resource does not make a same-named server in the other one
        // selectable — reading the tag alone did exactly that.
        assertEquals(listOf("exit", "exit@two"), catalog.selectable.map { it.tag })
    }
}
