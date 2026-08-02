package com.sladkaya.app.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class DataMatrixPayloadResolutionPolicyTest {
    @Test
    fun exactEightCharacterCameraPayloadStillRequiresVisibleConfirmation() {
        assertEquals(
            DataMatrixPackageCodeResolution.Ready(
                packageCode = "Ab1Zcd34",
                requiresUserConfirmation = true,
            ),
            DataMatrixPayloadResolutionPolicy.resolve("Ab1Zcd34"),
        )
    }

    @Test
    fun fullSibionicsGs1PayloadDerivesOnlyAVisibleUnverifiedCandidate() {
        val raw = "\u001d0106972831641803112412191725121810LT4F241247J\u001d21241247YEZ1450HAJ02"

        assertEquals(
            DataMatrixPackageCodeResolution.Ready(
                packageCode = "YEZ1450H",
                requiresUserConfirmation = true,
            ),
            DataMatrixPayloadResolutionPolicy.resolve(raw),
        )
    }

    @Test
    fun shortLightPackageUsesItsDocumentedCodeWindow() {
        val raw = "\u001d0106972831641476112412231725122210LT46241219C\u001d21WD9QAXGA52WS4V"

        assertEquals(
            DataMatrixPackageCodeResolution.Ready(
                packageCode = "QAXGA52W",
                requiresUserConfirmation = true,
            ),
            DataMatrixPayloadResolutionPolicy.resolve(raw),
        )
    }

    @Test
    fun remainingKnownGs1PackagingFixturesKeepTheirExactCodeWindows() {
        val fixtures = listOf(
            "\u001d0106972831641117112406121725061110LT48240601R\u001d21240601YL08230BFY73" to
                "YL08230B",
            "\u001d0106972831641476112504081726040710LT46250316C\u001d21P2250316015APD66" to
                "0316015A",
            "^]0106972831640165112312091724120810LT41231108C^]21231108GEPD802JPP76" to
                "GEPD802J",
        )

        fixtures.forEach { (raw, expectedCode) ->
            assertEquals(
                DataMatrixPackageCodeResolution.Ready(
                    packageCode = expectedCode,
                    requiresUserConfirmation = true,
                ),
                DataMatrixPayloadResolutionPolicy.resolve(raw),
            )
        }
    }

    @Test
    fun ai250CandidateAlsoRequiresVisibleUserConfirmation() {
        val raw = "]d20106972831641803250Ab1Zcd34"

        assertEquals(
            DataMatrixPackageCodeResolution.Ready(
                packageCode = "Ab1Zcd34",
                requiresUserConfirmation = true,
            ),
            DataMatrixPayloadResolutionPolicy.resolve(raw),
        )
    }

    @Test
    fun conflictingCandidatesNeverChooseOneSilently() {
        val raw = "01069728316418032112345ABCDEFGH9999\u001d250ZZZZ9999"

        assertEquals(
            DataMatrixPackageCodeResolution.ManualRequired(
                DataMatrixManualReason.CONFLICTING_CANDIDATES,
            ),
            DataMatrixPayloadResolutionPolicy.resolve(raw),
        )
    }

    @Test
    fun unknownManufacturerMalformedPayloadAndMissingCandidateFailClosed() {
        assertEquals(
            DataMatrixPackageCodeResolution.ManualRequired(
                DataMatrixManualReason.UNSUPPORTED_MANUFACTURER,
            ),
            DataMatrixPayloadResolutionPolicy.resolve("010123456789012821123456789012"),
        )
        assertEquals(
            DataMatrixPackageCodeResolution.ManualRequired(
                DataMatrixManualReason.INVALID_GS1_PAYLOAD,
            ),
            DataMatrixPayloadResolutionPolicy.resolve("not-a-valid-gs1-payload"),
        )
        assertEquals(
            DataMatrixPackageCodeResolution.ManualRequired(
                DataMatrixManualReason.CODE_NOT_DERIVABLE,
            ),
            DataMatrixPayloadResolutionPolicy.resolve("010697283164180321SHORT"),
        )
    }

    @Test
    fun gs3AndTransmitterSkusCannotEnterTheGs1SetupPath() {
        listOf("06972831642213", "06972831643005").forEach { gtin ->
            assertEquals(
                DataMatrixPackageCodeResolution.ManualRequired(
                    DataMatrixManualReason.GS3_REQUIRES_SEPARATE_SETUP,
                ),
                DataMatrixPayloadResolutionPolicy.resolve(
                    "01${gtin}21123456789012",
                ),
            )
        }
        assertEquals(
            DataMatrixPackageCodeResolution.ManualRequired(
                DataMatrixManualReason.TRANSMITTER_CODE_IS_NOT_SENSOR_CODE,
            ),
            DataMatrixPayloadResolutionPolicy.resolve(
                "010697283164148321123456789012",
            ),
        )
    }
}
