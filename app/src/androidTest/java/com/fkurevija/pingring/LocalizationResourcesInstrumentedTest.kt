package com.fkurevija.pingring

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fkurevija.pingring.data.radar.RadarAccessibilityProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
    fun chatReceiptLabelsAndCountFormattingAreTranslatedForEverySupportedLocale() {
        val baseContext: Context = ApplicationProvider.getApplicationContext()
        val english = contextFor(baseContext, "en")
        val expectedDetails = mapOf(
            "hr" to "Potvrde poruke",
            "es" to "Confirmaciones del mensaje",
            "fr" to "Confirmations du message",
            "fr-CA" to "Accusés du message",
            "de" to "Nachrichtenbestätigungen",
            "ru" to "Статус сообщения",
        )

        expectedDetails.forEach { (languageTag, translatedDetails) ->
            val localized = contextFor(baseContext, languageTag)
            assertEquals(translatedDetails, localized.getString(R.string.chat_receipt_details))
            listOf(
                R.string.read_receipts,
                R.string.chat_receipt_sent,
                R.string.chat_receipt_delivered,
                R.string.chat_receipt_read,
            ).forEach { resourceId ->
                assertNotEquals(
                    "Expected $languageTag to provide a translation for $resourceId",
                    english.getString(resourceId),
                    localized.getString(resourceId),
                )
            }

            val counts = localized.getString(R.string.chat_receipt_counts, 2, 3, 1, 3)
            assertTrue("$languageTag lost the delivered count: $counts", counts.contains("2/3"))
            assertTrue("$languageTag lost the read count: $counts", counts.contains("1/3"))
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