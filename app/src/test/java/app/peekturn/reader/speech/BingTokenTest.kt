package app.peekturn.reader.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-aloud handshake token at its real seam: a timestamp in, the token the service demands out,
 * with no network anywhere. The expected values were produced by the established client implementation
 * this handshake was reproduced from (the same one that completes the handshake against the live
 * service today), for the timestamps named here; they are the oracle, not this code's own output.
 */
class BingTokenTest {
    @Test fun theServiceTokenIsReproducedForTheKnownTimestamps() {
        assertEquals(
            "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
            bingSecMsGec(1700000000.0)
        )
        assertEquals(
            "B029A7426816F097-4579D112DC47FC3BF432B499C0A282F427C659474AF9BF5FA33CE2C003F9E9C2",
            bingSecMsGec(1750000000.0)
        )
        assertEquals(
            "D4216A7D3DE0C94F-91D741832FD084A110FDAD52BE2FFA569C6EA5F7D06A9429380A398738F4559A",
            bingSecMsGec(1790000000.0)
        )
        assertEquals(
            "99E54519B490BE5C-E162DB0977FBDCB9B86DD8148BF1BCDC3C0303176CE39852A42B415935C20654",
            bingSecMsGec(1800000000.5)
        )
        assertEquals(
            "14EC1E205BF34358-B348272BD2946E0C51DAD7886A19FCD61E7001AD44CAE92C31AFAF53CDD0D1CE",
            bingSecMsGec(1689000123.0)
        )
    }

    /** The token is the service's two-part shape: a keyed half, a dash, then a 64-digit digest. */
    @Test fun everyTokenHasTheShapeTheServiceValidates() {
        for (seconds in listOf(0.0, 1.0, 1700000000.0, 1700000300.0, 99_999_999_999.5, 1_000_000_000_000.0)) {
            val token = bingSecMsGec(seconds)
            assertEquals("Two uppercase hex halves, 16 then 64 digits", 81, token.length)
            assertEquals('-', token[16])
            assertTrue(token.take(16).all { it in "0123456789ABCDEF" })
            assertTrue(token.drop(17).all { it in "0123456789ABCDEF" })
        }
    }

    /** One timestamp, one token: the same second and the same five-minute step must be repeatable. */
    @Test fun theTokenIsStableWithinAStepAndMovesWithIt() {
        assertEquals(bingSecMsGec(1750000000.0), bingSecMsGec(1750000000.0))
        assertEquals("A fractional clock inside the step reads the same token",
            bingSecMsGec(1750000000.0), bingSecMsGec(1750000001.25))
        assertNotEquals(bingSecMsGec(1700000000.0), bingSecMsGec(1700000300.0))
        assertNotEquals(bingSecMsGec(1750000000.0), bingSecMsGec(1790000000.0))
    }
}
