package io.hydrabox.ui.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RussianCopyTest {
    private val russian = File("src/commonMain/composeResources/values-ru/strings.xml").readText()

    @Test fun `russian copy uses one neutral vocabulary`() {
        mapOf(
            "home_empty_title" to "Нет подписки",
            "home_row_exit" to "Внешний IP",
            "home_row_plan" to "Лимит",
            "server_auto" to "Автовыбор",
            "servers_measure" to "Измерить задержку",
            "settings_mode_proxy" to "Прокси",
            "apps_mode_bypass" to "Выбранные приложения — напрямую",
            "traffic_unavailable" to "Статистика появится после начала передачи данных.",
            "settings_proxy_lan" to "Доступ к прокси из локальной сети",
            "settings_support" to "Информация и диагностика",
        ).forEach { (name, value) ->
            assertContains(russian, "<string name=\"$name\">$value</string>")
        }
    }

    @Test fun `infinite term wording stays unchanged`() {
        assertContains(russian, "<string name=\"home_days_left_unlimited\">Осталось ∞ дн.</string>")
    }

    @Test fun `home and apps do not repeat the same facts`() {
        val home = File("src/commonMain/kotlin/io/hydrabox/ui/app/HomeScreen.kt").readText()
        val details = File("src/commonMain/kotlin/io/hydrabox/ui/app/DetailScreens.kt").readText()
        assertEquals(1, Regex("state\\.exit\\.countryCode").findAll(home).count())
        assertFalse("Res.string.apps_body_" in details)
    }

    @Test fun `resource files contain only copy used by the interface`() {
        val code = File("src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        val names = Regex("<string name=\"([^\"]+)\"").findAll(russian).map { it.groupValues[1] }.toList()
        assertEquals(emptyList(), names.filterNot { "Res.string.$it" in code })
    }
}
