package tw.apostar.notificationpaytest

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

data class GoogleWalletTransaction(
    val shop: String,
    val amount: String,
    val date: String,
    val capturedAt: Long,
    val cardName: String = "",
    val bank: String = "",
    val cardLast4: String = "",
    val cardType: String = "",
    val transactionId: String = "",
    val transactionType: String = "",
    val virtualCardLast4: String = "",
    val virtualCardType: String = "",
    val cardMatchSource: String = "",
    val detailChecked: Boolean = false
) {
    val fallbackKey: String get() = "${date.substringBefore(' ')}|$shop|$amount"
    val key: String get() = transactionId.ifBlank { fallbackKey }
}

object GoogleWalletTransactionStore {
    private const val PREF = "google_wallet_transactions"
    private const val KEY = "items"
    private const val MAX = 500

    private fun loadRaw(c: Context): List<GoogleWalletTransaction> = try {
        val raw = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GoogleWalletTransaction(
                shop = o.optString("shop"), amount = o.optString("amount"), date = o.optString("date"),
                capturedAt = o.optLong("capturedAt"), cardName = o.optString("cardName"), bank = o.optString("bank"),
                cardLast4 = o.optString("cardLast4"), cardType = o.optString("cardType"), transactionId = o.optString("transactionId"),
                transactionType = o.optString("transactionType"), virtualCardLast4 = o.optString("virtualCardLast4"),
                virtualCardType = o.optString("virtualCardType"), cardMatchSource = o.optString("cardMatchSource"),
                detailChecked = o.optBoolean("detailChecked", false)
            )
        }
    } catch (_: Exception) { emptyList() }

    fun load(c: Context): List<GoogleWalletTransaction> = normalize(loadRaw(c), false)

    /**
     * De-duplicate legacy Google Wallet rows without destroying card metadata.
     *
     * Older versions used normalize(raw, true), which cleared bank/cardName/cardLast4
     * from every row whose transactionId happened to be blank. Because this method is
     * called at the start of every sync, a card successfully recovered from a detail
     * page could be erased again on the very next run. Keep all recovered metadata and
     * only perform normal de-duplication here.
     */
    fun compactLegacyGlobalHistory(c: Context): Int {
        val raw = loadRaw(c)
        val normalized = normalize(raw, false)
        save(c, normalized)
        return (raw.size - normalized.size).coerceAtLeast(0)
    }

    fun pruneUnresolvedOlderThan(c: Context, days: Int): Int {
        val current = load(c)
        val cutoff = System.currentTimeMillis() - days.toLong() * 86_400_000L
        val kept = current.filter { x ->
            if (x.detailChecked || x.transactionId.isNotBlank()) true
            else parseDayMillis(x.date)?.let { it >= cutoff } ?: true
        }
        save(c, kept)
        return current.size - kept.size
    }

    fun unresolved(c: Context, maxAgeDays: Int? = null): List<GoogleWalletTransaction> {
        val cutoff = maxAgeDays?.let { System.currentTimeMillis() - it.toLong() * 86_400_000L }
        return load(c).filter { x ->
            !x.detailChecked && (cutoff == null || (parseDayMillis(x.date)?.let { it >= cutoff } ?: true))
        }
    }

    fun unresolvedSummary(c: Context, maxAgeDays: Int? = null, limit: Int = 12): String =
        unresolved(c, maxAgeDays).take(limit).joinToString(" || ") { it.fallbackKey }

    fun merge(c: Context, incoming: List<GoogleWalletTransaction>): Int {
        if (incoming.isEmpty()) return 0
        val current = load(c).toMutableList()
        var added = 0

        incoming.forEach { x ->
            val xDay = x.date.substringBefore(' ')

            for (i in current.indices) {
                val old = current[i]
                val aliasMatch = !old.detailChecked && old.transactionId.isBlank() &&
                    old.date.substringBefore(' ') == xDay &&
                    amountsEquivalent(old.amount, x.amount) &&
                    isSuspiciousMerchant(old.shop) &&
                    x.shop.trim().length > old.shop.trim().length
                if (aliasMatch) {
                    current[i] = old.copy(
                        shop = x.shop.trim(),
                        amount = canonicalAmount(old.amount, x.amount),
                        capturedAt = maxOf(old.capturedAt, x.capturedAt)
                    )
                }
            }

            val normalizedNow = normalize(current, false)
            current.clear()
            current.addAll(normalizedNow)

            val index = current.indexOfFirst { it.key == x.key || it.fallbackKey == x.fallbackKey || sameFallbackIdentity(it, x) }
            if (index < 0) {
                current.add(x)
                added++
            } else {
                val base = current[index]
                val merged = mergePair(base, x, false).copy(
                    shop = if (x.shop.trim().length > base.shop.trim().length && isSuspiciousMerchant(base.shop)) x.shop.trim() else base.shop
                )
                current[index] = merged
            }
        }

        save(c, normalize(current, false))
        return added
    }

    fun updateDetail(
        c: Context, lookupKey: String, exactDateTime: String, transactionId: String, transactionType: String,
        virtualCardLast4: String, virtualCardType: String, cardName: String, bank: String, cardLast4: String,
        cardType: String, cardMatchSource: String
    ): Boolean {
        val current = load(c).toMutableList()
        val index = current.indexOfFirst { it.key == lookupKey || it.fallbackKey == lookupKey }
        if (index < 0) return false
        val old = current[index]
        current[index] = old.copy(
            date = exactDateTime.ifBlank { old.date }, transactionId = transactionId.ifBlank { old.transactionId },
            transactionType = transactionType.ifBlank { old.transactionType }, virtualCardLast4 = virtualCardLast4.ifBlank { old.virtualCardLast4 },
            virtualCardType = virtualCardType.ifBlank { old.virtualCardType }, cardName = cardName.ifBlank { old.cardName },
            bank = bank.ifBlank { old.bank }, cardLast4 = cardLast4.ifBlank { old.cardLast4 }, cardType = cardType.ifBlank { old.cardType },
            cardMatchSource = cardMatchSource.ifBlank { old.cardMatchSource },
            detailChecked = exactDateTime.isNotBlank() || transactionId.isNotBlank(), capturedAt = System.currentTimeMillis()
        )
        save(c, normalize(current, false))
        return true
    }

    fun updateDetailTime(c: Context, key: String, exactDateTime: String): Boolean =
        updateDetail(c, key, exactDateTime, "", "", "", "", "", "", "", "", "")

    fun isDetailChecked(c: Context, key: String): Boolean =
        load(c).firstOrNull { it.key == key || it.fallbackKey == key }?.detailChecked == true

    private fun normalize(input: List<GoogleWalletTransaction>, resetLegacyGlobal: Boolean): List<GoogleWalletTransaction> {
        val out = mutableListOf<GoogleWalletTransaction>()
        input.sortedBy { it.capturedAt }.forEach { raw ->
            val x = if (resetLegacyGlobal && raw.transactionId.isBlank()) raw.copy(
                cardName = "", bank = "", cardLast4 = "", cardType = "", cardMatchSource = "", detailChecked = false
            ) else raw
            val index = out.indexOfFirst { it.key == x.key || it.fallbackKey == x.fallbackKey || sameFallbackIdentity(it, x) }
            if (index < 0) out.add(x) else out[index] = mergePair(out[index], x, resetLegacyGlobal)
        }
        return out.sortedByDescending { it.date }.take(MAX)
    }

    private fun mergePair(a: GoogleWalletTransaction, b: GoogleWalletTransaction, resetLegacyGlobal: Boolean): GoogleWalletTransaction {
        val cardConflict = a.cardLast4.isNotBlank() && b.cardLast4.isNotBlank() && a.cardLast4 != b.cardLast4
        val exactA = a.date.isNotBlank() && !a.date.endsWith(" 12:00:00")
        val exactB = b.date.isNotBlank() && !b.date.endsWith(" 12:00:00")
        val preferredDate = when { exactB -> b.date; exactA -> a.date; b.date.isNotBlank() -> b.date; else -> a.date }
        val clearLegacyCard = resetLegacyGlobal || cardConflict
        val preferredShop = when {
            isSuspiciousMerchant(a.shop) && b.shop.trim().length > a.shop.trim().length -> b.shop.trim()
            isSuspiciousMerchant(b.shop) && a.shop.trim().length > b.shop.trim().length -> a.shop.trim()
            b.shop.isNotBlank() -> b.shop
            else -> a.shop
        }
        return a.copy(
            shop = preferredShop,
            amount = canonicalAmount(a.amount, b.amount),
            date = preferredDate, capturedAt = maxOf(a.capturedAt, b.capturedAt),
            cardName = if (clearLegacyCard) "" else b.cardName.ifBlank { a.cardName },
            bank = if (clearLegacyCard) "" else b.bank.ifBlank { a.bank },
            cardLast4 = if (clearLegacyCard) "" else b.cardLast4.ifBlank { a.cardLast4 },
            cardType = if (clearLegacyCard) "" else b.cardType.ifBlank { a.cardType },
            transactionId = b.transactionId.ifBlank { a.transactionId }, transactionType = b.transactionType.ifBlank { a.transactionType },
            virtualCardLast4 = b.virtualCardLast4.ifBlank { a.virtualCardLast4 }, virtualCardType = b.virtualCardType.ifBlank { a.virtualCardType },
            cardMatchSource = if (clearLegacyCard) "" else b.cardMatchSource.ifBlank { a.cardMatchSource },
            detailChecked = if (resetLegacyGlobal && a.transactionId.isBlank() && b.transactionId.isBlank()) false else a.detailChecked || b.detailChecked
        )
    }

    private fun sameFallbackIdentity(a: GoogleWalletTransaction, b: GoogleWalletTransaction): Boolean =
        a.date.substringBefore(' ') == b.date.substringBefore(' ') &&
            amountsEquivalent(a.amount, b.amount) &&
            a.shop.trim() == b.shop.trim()

    private fun isSuspiciousMerchant(shop: String): Boolean {
        val s = shop.trim()
        if (s.isBlank() || s.length > 2) return false
        return s.none { it.isDigit() } && s !in setOf("全部", "交易", "搜尋", "返回")
    }

    private fun cleanAmount(amount: String): String = amount.replace("$", "").replace(",", "").trim()

    private fun amountNumber(amount: String): java.math.BigDecimal? = try {
        cleanAmount(amount).toBigDecimal().stripTrailingZeros()
    } catch (_: Throwable) { null }

    private fun amountsEquivalent(a: String, b: String): Boolean {
        val na = amountNumber(a)
        val nb = amountNumber(b)
        return if (na != null && nb != null) na.compareTo(nb) == 0 else cleanAmount(a) == cleanAmount(b)
    }

    private fun canonicalAmount(a: String, b: String): String {
        val n = amountNumber(b) ?: amountNumber(a) ?: return b.ifBlank { a }
        return n.setScale(2).toPlainString()
    }

    private fun parseDayMillis(date: String): Long? = try {
        val f = SimpleDateFormat("yyyy/MM/dd", Locale.US); f.isLenient = false
        f.parse(date.substringBefore(' '))?.time
    } catch (_: Throwable) { null }

    private fun save(c: Context, list: List<GoogleWalletTransaction>) {
        val arr = JSONArray()
        list.take(MAX).forEach { x ->
            arr.put(JSONObject().put("shop", x.shop).put("amount", x.amount).put("date", x.date).put("capturedAt", x.capturedAt)
                .put("cardName", x.cardName).put("bank", x.bank).put("cardLast4", x.cardLast4).put("cardType", x.cardType)
                .put("transactionId", x.transactionId).put("transactionType", x.transactionType).put("virtualCardLast4", x.virtualCardLast4)
                .put("virtualCardType", x.virtualCardType).put("cardMatchSource", x.cardMatchSource).put("detailChecked", x.detailChecked))
        }
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).commit()
    }
}
