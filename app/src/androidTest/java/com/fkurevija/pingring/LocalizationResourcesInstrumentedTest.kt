package com.fkurevija.pingring

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fkurevija.pingring.data.RadarAccessibilityProbe
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationResourcesInstrumentedTest {

    @Test
    fun supportedLocalesResolveTheirTranslations() {
        val baseContext: Context = ApplicationProvider.getApplicationContext()
        val expectedSettings = mapOf(
            "hr" to "Postavke",
            "es" to "Ajustes",
            "fr" to "Paramètres",
            "fr-CA" to "Réglages",
            "de" to "Einstellungen",
            "ru" to "Настройки",
        )

        expectedSettings.forEach { (languageTag, expected) ->
            assertEquals(expected, contextFor(baseContext, languageTag).getString(R.string.settings))
        }
    }

    @Test
    fun slavicJoinRequestCountsUseTheirLocalePluralForms() {
        val baseContext: Context = ApplicationProvider.getApplicationContext()
        val croatian = contextFor(baseContext, "hr")
        val russian = contextFor(baseContext, "ru")

        assertEquals(
            "2 uređaja želi se pridružiti",
            croatian.resources.getQuantityString(R.plurals.join_request_count, 2, 2),
        )
        assertEquals(
            "2 устройства хотят присоединиться",
            russian.resources.getQuantityString(R.plurals.join_request_count, 2, 2),
        )
    }

    @Test
    fun spokenRadarSummaryUsesLocalizedResources() {
        val baseContext: Context = ApplicationProvider.getApplicationContext()
        val russian = contextFor(baseContext, "ru")

        assertEquals(
            "Радар: Анна. Сигнал: Рядом. Направление недоступно.",
            RadarAccessibilityProbe.describeLocalized(
                resources = russian.resources,
                peerName = "Анна",
                distanceMeters = null,
                azimuthDegrees = null,
                proximityLabel = russian.getString(R.string.proximity_hot),
                beaconTitle = null,
            ),
        )
    }

    private fun contextFor(baseContext: Context, languageTag: String): Context {
        val configuration = Configuration(baseContext.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(languageTag))
        }
        return baseContext.createConfigurationContext(configuration)
    }
}