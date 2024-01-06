// Import Nylas packages
import com.nylas.NylasClient
import com.nylas.models.FindEventQueryParams

// Import Spark and Jackson packages
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import spark.template.mustache.MustacheTemplateEngine;
import com.nylas.models.When

import spark.ModelAndView
import spark.kotlin.Http
import spark.kotlin.ignite

import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.net.URLEncoder
import java.text.SimpleDateFormat

data class Webhook_Info(
    var id: String,
    var date: String,
    var title: String,
    var description: String,
    var participants: String,
    var status: String
)

var array: Array<Webhook_Info> = arrayOf()

object Hmac {
    fun digest(
        msg: String,
        key: String,
        alg: String = "HmacSHA256"
    ): String {
        val signingKey = SecretKeySpec(key.toByteArray(), alg)
        val mac = Mac.getInstance(alg)
        mac.init(signingKey)

        val bytes = mac.doFinal(msg.toByteArray())
        return format(bytes)
    }

    private fun format(bytes: ByteArray): String {
        val formatter = Formatter()
        bytes.forEach { formatter.format("%02x", it) }
        return formatter.toString()
    }
}

fun addElement(arr: Array<Webhook_Info>, element: Webhook_Info): Array<Webhook_Info> {
    val mutableArray = arr.toMutableList()
    mutableArray.add(element)
    return mutableArray.toTypedArray()
}

fun dateFormatter(milliseconds: String): String {
    return SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(Date(milliseconds.toLong() * 1000)).toString()
}

fun main(args: Array<String>) {
    val http: Http = ignite()
    // Initialize Nylas client
    val nylas: NylasClient = NylasClient(
        apiKey = System.getenv("V3_TOKEN")
    )

    http.get("/webhook") {
        request.queryParams("challenge")
    }

    http.post("/webhook") {
        val mapper = jacksonObjectMapper()
        val model: JsonNode = mapper.readValue<JsonNode>(request.body())
        if(model["data"]["object"]["calendar_id"].textValue().equals(System.getenv("CALENDAR_ID"), false)){
            if(Hmac.digest(request.body(), URLEncoder.encode(System.getenv("CLIENT_SECRET"), "UTF-8")) == request.headers("X-Nylas-Signature").toString()){
                val eventquery = FindEventQueryParams(System.getenv("CALENDAR_ID"))
                System.out.println(model["data"]["object"]["id"].textValue())
                val myevent = nylas.events().find(System.getenv("GRANT_ID"), eventId = model["data"]["object"]["id"].textValue(), queryParams = eventquery)
                var participants: String = ""
                for (participant in myevent.data.participants){
                    participants = "$participants;${participant.email.toString()}"
                }
                var event_datetime: String = ""
                when(myevent.data.getWhen().getObject().toString()) {
                    "DATESPAN" -> {
                        val datespan = myevent.data.getWhen() as When.Datespan
                        event_datetime = datespan.startDate.toString()
                    }
                    "TIMESPAN" -> {
                        val timespan = myevent.data.getWhen() as When.Timespan
                        val startDate = dateFormatter(timespan.startTime.toString())
                        val endDate = dateFormatter(timespan.endTime.toString())
                        event_datetime = "From $startDate to $endDate"
                    }
                    "DATE" -> {
                        val datespan = myevent.data.getWhen() as When.Date
                        event_datetime = datespan.date
                    }
                }
                participants = participants.drop(1)
                array = addElement(array, Webhook_Info(myevent.data.id, event_datetime.toString(), myevent.data.title.toString(),
                                   myevent.data.description.toString(), participants, myevent.data.status.toString()))
            }
        }
        ""
    }

    http.get("/") {
        val model = HashMap<String, Any>()
        model["webhooks"] = array
        MustacheTemplateEngine().render(
            ModelAndView(model, "show_webhooks.mustache")
        )
    }
}
