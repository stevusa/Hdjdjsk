package rs.fiskalhtml

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import rs.fiskalhtml.databinding.ActivityMainBinding
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var pendingHtml = ""
    private var lastJson = ""
    private var parsedItems: List<ReceiptItem> = emptyList()
    private var isValid: Boolean? = null

    data class ReceiptItem(
        val name: String,
        val quantity: String,
        val unitPrice: String,
        val totalPrice: String,
        val gtin: String
    )

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (!text.isNullOrBlank()) {
            b.etQr.setText(text)
            fetchReceipt(text)
        }
    }

    private val createHtmlLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(pendingHtml.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "HTML fajl je sačuvan.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnScan.setOnClickListener {
            val options = ScanOptions().apply {
                setPrompt("Usmeri kameru ka QR kodu fiskalnog računa")
                setBeepEnabled(true)
                setOrientationLocked(false)
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            }
            scanLauncher.launch(options)
        }

        b.btnFetch.setOnClickListener {
            fetchReceipt(b.etQr.text?.toString()?.trim().orEmpty())
        }

        b.btnOpenPfr.setOnClickListener {
            val value = b.etQr.text?.toString()?.trim().orEmpty()
            if (isAllowedPfrUrl(value)) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
            } else {
                Toast.makeText(this, "QR ne sadrži podržan PFR HTTPS link.", Toast.LENGTH_SHORT).show()
            }
        }

        b.btnExport.setOnClickListener {
            pendingHtml = buildHtml()
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
            val number = safeFilename(b.etReceiptNo.text?.toString().orEmpty())
            val suffix = if (number.isNotBlank()) "_$number" else "_$stamp"
            createHtmlLauncher.launch("fiskalni-racun$suffix.html")
        }
    }

    private fun fetchReceipt(urlText: String) {
        if (!isAllowedPfrUrl(urlText)) {
            b.tvStatus.text = "Status: QR nije podržan PFR HTTPS link"
            Toast.makeText(this, "Skeniraj QR sa fiskalnog računa.", Toast.LENGTH_SHORT).show()
            return
        }

        b.tvStatus.text = "Status: preuzimam podatke..."
        b.btnFetch.isEnabled = false

        Thread {
            try {
                val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 15000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "FiskalHTML/2.0 Android")
                    instanceFollowRedirects = true
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                conn.disconnect()

                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code")
                }

                val root = JSONObject(body)
                runOnUiThread {
                    lastJson = body
                    applyJson(root)
                    b.btnFetch.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    b.btnFetch.isEnabled = true
                    b.tvStatus.text = "Status: automatsko preuzimanje nije uspelo"
                    Toast.makeText(
                        this,
                        "Nije moguće automatski preuzeti podatke: ${e.message ?: "greška"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun applyJson(root: JSONObject) {
        val req = root.optJSONObject("invoiceRequest")
        val res = root.optJSONObject("invoiceResult")

        if (req == null || res == null) {
            b.tvStatus.text = "Status: PFR odgovor nema očekivanu strukturu"
            return
        }

        b.etSeller.setText(req.optString("businessName", ""))
        b.etPib.setText(req.optString("taxId", ""))

        val locationParts = listOf(
            req.optString("locationName", ""),
            req.optString("address", ""),
            req.optString("city", "")
        ).filter { it.isNotBlank() && it != "null" }
        b.etLocation.setText(locationParts.joinToString(", "))

        b.etReceiptNo.setText(res.optString("invoiceNumber", ""))
        b.etDate.setText(res.optString("sdcTime", ""))

        val total = if (res.has("totalAmount") && !res.isNull("totalAmount")) {
            res.optDouble("totalAmount", 0.0)
        } else 0.0
        b.etTotal.setText(String.format(Locale.US, "%.2f", total))

        val itemsArray = req.optJSONArray("items")
        val list = mutableListOf<ReceiptItem>()
        if (itemsArray != null) {
            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.optJSONObject(i) ?: continue
                list += ReceiptItem(
                    name = item.optString("name", ""),
                    quantity = numberAsText(item, "quantity"),
                    unitPrice = numberAsText(item, "unitPrice"),
                    totalPrice = numberAsText(item, "totalPrice"),
                    gtin = item.optString("gtin", "").takeUnless { it == "null" } ?: ""
                )
            }
        }
        parsedItems = list

        b.etItems.setText(
            if (list.isEmpty()) "Nema stavki u odgovoru."
            else list.joinToString("\n") {
                "${it.name} | ${it.quantity} × ${it.unitPrice} = ${it.totalPrice} RSD"
            }
        )

        isValid = if (root.has("isValid") && !root.isNull("isValid")) root.optBoolean("isValid") else null
        b.tvStatus.text = when (isValid) {
            true -> "Status: račun je validan ✓"
            false -> "Status: račun nije validan ✗"
            null -> "Status: podaci su učitani"
        }
    }

    private fun numberAsText(obj: JSONObject, key: String): String {
        if (!obj.has(key) || obj.isNull(key)) return ""
        return obj.get(key).toString()
    }

    private fun isAllowedPfrUrl(value: String): Boolean {
        return try {
            val uri = Uri.parse(value)
            val host = uri.host?.lowercase(Locale.US) ?: return false
            uri.scheme.equals("https", ignoreCase = true) &&
                (host == "suf.purs.gov.rs" || host.endsWith(".suf.purs.gov.rs"))
        } catch (_: Exception) {
            false
        }
    }

    private fun buildHtml(): String {
        val seller = esc(b.etSeller.text?.toString())
        val pib = esc(b.etPib.text?.toString())
        val location = esc(b.etLocation.text?.toString())
        val receiptNo = esc(b.etReceiptNo.text?.toString())
        val date = esc(b.etDate.text?.toString())
        val total = esc(b.etTotal.text?.toString())
        val qrRaw = b.etQr.text?.toString()?.trim().orEmpty()
        val qr = esc(qrRaw)

        val validText = when (isValid) {
            true -> "VALIDAN"
            false -> "NIJE VALIDAN"
            null -> "NEPOZNATO"
        }

        val itemsHtml = if (parsedItems.isNotEmpty()) {
            parsedItems.joinToString("\n") { item ->
                """
                <tr>
                    <td>${esc(item.name)}</td>
                    <td>${esc(item.quantity)}</td>
                    <td>${esc(item.unitPrice)}</td>
                    <td>${esc(item.totalPrice)}</td>
                    <td>${esc(item.gtin)}</td>
                </tr>
                """.trimIndent()
            }
        } else {
            val manual = esc(b.etItems.text?.toString()).replace("\n", "<br>")
            """<tr><td colspan="5">$manual</td></tr>"""
        }

        val rawJsonBlock = if (lastJson.isNotBlank()) {
            """<details><summary>Originalni JSON odgovor</summary><pre>${esc(lastJson)}</pre></details>"""
        } else ""

        return """
<!doctype html>
<html lang="sr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Fiskalni račun - $receiptNo</title>
<style>
body{font-family:Arial,sans-serif;margin:0;background:#f4f6f8;color:#1f2937}
.wrap{max-width:920px;margin:24px auto;padding:16px}
.card{background:white;border-radius:16px;padding:22px;box-shadow:0 4px 18px rgba(0,0,0,.08)}
h1{margin-top:0}
.meta,.items{width:100%;border-collapse:collapse;margin:16px 0}
.meta td,.items th,.items td{border-bottom:1px solid #e5e7eb;padding:10px 7px;text-align:left}
.meta td:first-child{width:32%;font-weight:bold;color:#4b5563}
.items th{background:#f9fafb}
.total{font-size:24px;font-weight:bold;margin:18px 0}
.badge{display:inline-block;padding:7px 11px;border:1px solid #d1d5db;border-radius:999px;font-weight:bold}
.pfr{display:inline-block;padding:12px 16px;border-radius:10px;text-decoration:none;background:#111827;color:white}
.raw{margin-top:18px;padding:12px;background:#f9fafb;border-radius:10px;word-break:break-all}
pre{white-space:pre-wrap;word-break:break-word;background:#f9fafb;padding:12px;border-radius:10px}
@media print{body{background:white}.wrap{margin:0;max-width:none}.card{box-shadow:none}.pfr{color:black;background:white;border:1px solid #aaa}}
</style>
</head>
<body>
<div class="wrap"><div class="card">
<h1>Fiskalni račun</h1>
<div class="badge">PFR status: $validText</div>

<table class="meta">
<tr><td>Prodavac</td><td>$seller</td></tr>
<tr><td>PIB</td><td>$pib</td></tr>
<tr><td>Lokacija / adresa</td><td>$location</td></tr>
<tr><td>PFR broj računa</td><td>$receiptNo</td></tr>
<tr><td>PFR vreme</td><td>$date</td></tr>
</table>

<div class="total">Ukupno: $total RSD</div>

<h2>Stavke</h2>
<table class="items">
<thead><tr><th>Naziv</th><th>Količina</th><th>Jed. cena</th><th>Ukupno</th><th>GTIN</th></tr></thead>
<tbody>
$itemsHtml
</tbody>
</table>

<h2>PFR provera</h2>
<a class="pfr" href="$qr" target="_blank" rel="noopener">Otvori zvaničnu proveru računa</a>

<div class="raw"><strong>QR/PFR URL:</strong><br>$qr</div>
$rawJsonBlock
</div></div>
</body>
</html>
""".trimIndent()
    }

    private fun safeFilename(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
    }

    private fun esc(value: String?): String {
        return (value ?: "")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
