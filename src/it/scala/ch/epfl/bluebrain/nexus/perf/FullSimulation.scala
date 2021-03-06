package ch.epfl.bluebrain.nexus.perf
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

import ch.epfl.bluebrain.nexus.commons.test.Resources
import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import io.gatling.core.Predef._
import io.gatling.http.Predef._

import scala.concurrent.duration._

class FullSimulation extends BaseSimulation with Resources {

  val journeyDuration = config.fullSimulationConfig.duration

  val project = config.fullSimulationConfig.project

  val esQueries = jsonContentOf("/es-queries.json").hcursor
    .downField("queries")
    .values
    .get
    .map(json => Map("query" -> json.noSpaces))
    .toArray
    .random

  val blazegraphQueries = List(
    Map("query" -> contentOf("/person-by-type.sparql")),
    Map("query" -> contentOf("/subject-by-species.sparql")),
    Map("query" -> contentOf("/traces-by-species.sparql")),
    Map("query" -> contentOf("/traces-by-type.sparql"))
  ).toArray.random

  val fetch = exec { session =>
    val rnd = ThreadLocalRandom
      .current()
      .nextInt(session("search_total").as[Int]) + 1
    val s = session("schema").as[String]
    session.set("encodedId", URLEncoder.encode(s"$s/ids/$rnd", "UTF-8"))
  }.exec(
    http("Get Resource By Id")
      .get(s"/resources/perftestorg/perftestproj$project/$${encodedSchema}/$${encodedId}")
  )

  val fetchAndUpdate = exec { session =>
    val rnd = ThreadLocalRandom
      .current()
      .nextInt(session("search_total").as[Int]) + 1
    val s = session("schema").as[String]
    session.set("encodedId", URLEncoder.encode(s"$s/ids/$rnd", "UTF-8"))
  }.exec(
      http("Get Resource By Id")
        .get(s"/resources/perftestorg/perftestproj$project/$${encodedSchema}/$${encodedId}")
        .check(bodyString.saveAs("savedPayload"))
    )
    .exec { session =>
      val json     = parse(session("savedPayload").as[String]).right.get
      val revision = json.asObject.getOrElse(JsonObject())("_rev").flatMap(_.asNumber).flatMap(_.toInt).get
      val update = json.mapObject { obj =>
        obj
          .filterKeys(s => !s.startsWith("_"))
          .add(s"nxv:updated${revision + 1}", Json.fromString(s"${UUID.randomUUID().toString}"))
      }
      session.set("updateRevision", revision).set("updatePayload", update.spaces2)
    }
    .exec(
      http("Update Resource")
        .put(s"/resources/perftestorg/perftestproj$project/$${encodedSchema}/$${encodedId}?rev=$${updateRevision}")
        .body(StringBody("${updatePayload}"))
        .header("Content-Type", "application/json")
    )

  val fetchAndGetByRevision = exec { session =>
    val rnd = ThreadLocalRandom
      .current()
      .nextInt(session("search_total").as[Int]) + 1
    val s = session("schema").as[String]
    session.set("encodedId", URLEncoder.encode(s"$s/ids/$rnd", "UTF-8"))
  }.exec(
      http("Get Resource By Id")
        .get(s"/resources/perftestorg/perftestproj$project/$${encodedSchema}/$${encodedId}")
        .check(jsonPath("$.._rev").ofType[Int].saveAs("revisions"))
    )
    .exec { session =>
      val rnd = ThreadLocalRandom
        .current()
        .nextInt(session("revisions").as[Int]) + 1
      session.set("revisionToFetch", rnd)
    }
    .exec(
      http("Get Resource By Id And Rev")
        .get(s"/resources/perftestorg/perftestproj$project/$${encodedSchema}/$${encodedId}?rev=$${revisionToFetch}")
    )

  val esSearch = feed(esQueries).exec(
    http("ElasticSearch Query")
      .post(s"/views/perftestorg/perftestproj$project/nxv:defaultElasticIndex/_search")
      .body(StringBody("${query}"))
      .asJson
  )

  val blazegraphSearch = feed(blazegraphQueries).exec(
    http("BlazeGraph Query")
      .post(s"/views/perftestorg/perftestproj$project/nxv:defaultSparqlIndex/sparql")
      .body(StringBody("${query}"))
      .header("Content-Type", "text/plain"))

  val scn = scenario("FullSimulation")
    .feed(schemasFeeder)
    .exec { session =>
      val s = session("schema").as[String]
      session.set("encodedSchema", URLEncoder.encode(s, "UTF-8"))
    }
    .tryMax(config.http.retries)(
      exec(
        http("List Resources")
          .get(s"/resources/perftestorg/perftestproj$project/$${encodedSchema}")
          check jsonPath("$.._total").ofType[Int].saveAs("search_total"))
        .repeat(config.fullSimulationConfig.repeats)(
          randomSwitch(
            config.fullSimulationConfig.fetchPercentage                 -> fetch,
            config.fullSimulationConfig.fetchAndUpdatePercentage        -> fetchAndUpdate,
            config.fullSimulationConfig.fetchAndGetByRevisionPercentage -> fetchAndGetByRevision,
            config.fullSimulationConfig.blazegraphSearchPercentage      -> blazegraphSearch,
            config.fullSimulationConfig.esSearchPercentage              -> esSearch
          )
        )
    )

  setUp(
    scn
      .inject(
        rampConcurrentUsers(0) to config.fullSimulationConfig.users during (1 minutes),
        constantConcurrentUsers(config.fullSimulationConfig.users) during config.fullSimulationConfig.duration
      )
      .protocols(httpConf))
}
