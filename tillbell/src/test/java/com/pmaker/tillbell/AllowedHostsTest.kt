package com.pmaker.tillbell

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowedHostsTest {
    private val client = FakePlayBillingClient()
    private val billing = TillbellBilling(client, packageName = "app.example")
    private val allowed = setOf("Example.com")

    private fun from(url: String) = """{"metadata":{"url":"$url"}}"""

    @Test
    fun `every page is trusted without allowed hosts`() {
        assertTrue(isTrusted(from("https://elsewhere.test/"), null))
        assertTrue(isTrusted("{}", null))
    }

    @Test
    fun `only pages on an allowed host are trusted`() {
        assertTrue(isTrusted(from("https://example.com/paywall"), allowed))
        assertFalse(isTrusted(from("https://evil.test/example.com"), allowed))
        assertFalse(isTrusted(from("https://example.com.evil.test/"), allowed))
        assertFalse(isTrusted(from("not a url"), allowed))
        assertFalse(isTrusted("{}", allowed))
    }

    @Test
    fun `a page on another host gets an error reply and never reaches Google Play`() =
        runTest {
            val purchase =
                """{"productId":"premium","basePlanId":"monthly","obfuscatedAccountId":"a",""" +
                    """"metadata":{"url":"https://evil.test/"}}"""

            val reply = billing.answer("purchase", purchase, allowedHosts = allowed, activity = { null })

            assertEquals("""{"result":"error","message":"The page is not on an allowed host"}""", reply)
            assertEquals(0, client.connectCalls)
            assertNull(billing.answer("manage", from("https://evil.test/"), allowedHosts = allowed, activity = { null }))
        }
}
