package ch.epfl.bluebrain.nexus.tests.kg

import java.util.regex.Pattern.quote

import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.{RequestEntity, StatusCodes, HttpRequest => Req}
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport._
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import org.scalatest.concurrent.Eventually
import org.scalatest.{CancelAfterFailure, Inspectors}

class ResourcesSpec extends BaseSpec with Eventually with Inspectors with CancelAfterFailure {

  val orgId   = genId()
  val projId1 = genId()
  val projId2 = genId()
  val id1     = s"$orgId/$projId1"
  val id2     = s"$orgId/$projId2"

  "creating projects" should {

    "add projects/create, orgs/create, orgs/write, resources/create, resources/read, resources/write  permissions for user" in {
      val json = jsonContentOf(
        "/iam/add.json",
        replSub + (quote("{perms}") -> """projects/create","projects/read","orgs/write","resources/create","resources/read","resources/write","orgs/create""")
      ).toEntity
      cl(Req(PUT, s"$iamBase/acls/", headersGroup, json)).mapResp { result =>
        result.status shouldEqual StatusCodes.OK
        result.entity.isKnownEmpty() shouldEqual true
      }
    }

    "succeed if payload is correct" in {
      cl(Req(PUT, s"$adminBase/orgs/$orgId", headersUser, orgReqEntity())).mapResp { result =>
        result.status shouldEqual StatusCodes.Created
      }

      cl(Req(PUT, s"$adminBase/projects/$id1", headersUser, kgReqEntity())).mapResp { result =>
        result.status shouldEqual StatusCodes.Created
      }
    }
  }

  "adding schema" should {
    "create a schema" in {
      val schemaPayload = jsonContentOf("/kg/schemas/simple-schema.json")

      eventually {
        cl(Req(PUT, s"$kgBase/schemas/$id1/test-schema", headersUser, schemaPayload.toEntity)).mapResp { result =>
          result.status shouldEqual StatusCodes.Created
        }
      }
    }
  }

  "creating a resource" should {
    "succeed if the payload is correct" in {
      val payload = jsonContentOf("/kg/resources/simple-resource.json",
                                  Map(quote("{priority}") -> "5", quote("{resourceId}") -> "1"))

      eventually {
        cl(Req(PUT, s"$kgBase/resources/$id1/test-schema/test-resource:1", headersUser, payload.toEntity)).mapResp {
          result =>
            result.status shouldEqual StatusCodes.Created
        }
      }
    }

    "fetch the payload" in {
      cl(Req(GET, s"$kgBase/resources/$id1/test-schema/test-resource:1", headersUser)).mapJson { (json, result) =>
        val expected = jsonContentOf("/kg/resources/simple-resource-response.json",
                                     Map(quote("{priority}") -> "5", quote("{rev}") -> "1"))
        result.status shouldEqual StatusCodes.OK
        json.removeField("_createdAt").removeField("_updatedAt") shouldEqual expected
      }
    }
  }

  "cross-project resolvers" should {
    "fail if the schema doesn't exist in the project" in {
      val payload = jsonContentOf("/kg/resources/simple-resource.json",
                                  Map(quote("{priority}") -> "3", quote("{resourceId}") -> "1"))

      cl(Req(PUT, s"$kgBase/resources/$id2/test-schema/test-resource:1", headersUser, payload.toEntity)).mapResp {
        result =>
          result.status shouldEqual StatusCodes.NotFound
      }
    }

    "create a cross-project-resolver for proj2" in {
      val resolverPayload =
        jsonContentOf("/kg/resources/cross-project-resolver.json", Map(quote("{project}") -> id1))

      cl(Req(POST, s"$kgBase/resolvers/$id2/", headersUser, resolverPayload.toEntity)).mapResp { result =>
        result.status shouldEqual StatusCodes.Created
      }
    }

    "resolve schema from the other project" in {
      val payload = jsonContentOf("/kg/resources/simple-resource.json",
                                  Map(quote("{priority}") -> "3", quote("{resourceId}") -> "1"))

      eventually {
        cl(Req(PUT, s"$kgBase/resources/$id2/test-schema/test-resource:1", headersUser, payload.toEntity)).mapResp {
          result =>
            result.status shouldEqual StatusCodes.Created
        }
      }
    }

  }

  "updating a resource" should {
    "send the update" in {
      val payload = jsonContentOf("/kg/resources/simple-resource.json",
                                  Map(quote("{priority}") -> "3", quote("{resourceId}") -> "1"))

      cl(Req(PUT, s"$kgBase/resources/$id1/test-schema/test-resource:1?rev=1", headersUser, payload.toEntity)).mapResp {
        result =>
          result.status shouldEqual StatusCodes.OK
      }
    }
    "fetch the update" in {
      val expected = jsonContentOf("/kg/resources/simple-resource-response.json",
                                   Map(quote("{priority}") -> "3", quote("{rev}") -> "2"))
      cl(Req(GET, s"$kgBase/resources/$id1/test-schema/test-resource:1", headersUser)).mapJson { (json, result) =>
        result.status shouldEqual StatusCodes.OK
        json.removeField("_createdAt").removeField("_updatedAt") shouldEqual expected
      }
    }

    "fetch previous revision" in {
      val expected = jsonContentOf("/kg/resources/simple-resource-response.json",
                                   Map(quote("{priority}") -> "5", quote("{rev}") -> "1"))
      cl(Req(GET, s"$kgBase/resources/$id1/test-schema/test-resource:1?rev=1", headersUser)).mapJson { (json, result) =>
        result.status shouldEqual StatusCodes.OK
        json.removeField("_createdAt").removeField("_updatedAt") shouldEqual expected
      }
    }
  }

  "tagging a resource" should {

    "create a tag" in {
      val tag1 = jsonContentOf("/kg/resources/tag.json", Map(quote("{tag}") -> "v1.0.0", quote("{rev}") -> "1"))
      val tag2 = jsonContentOf("/kg/resources/tag.json", Map(quote("{tag}") -> "v1.0.1", quote("{rev}") -> "2"))

      cl(Req(PUT, s"$kgBase/resources/$id1/test-schema/test-resource:1/tags?rev=2", headersUser, tag1.toEntity))
        .mapResp { resp =>
          resp.status shouldEqual StatusCodes.Created
        }
      cl(Req(PUT, s"$kgBase/resources/$id1/test-schema/test-resource:1/tags?rev=3", headersUser, tag2.toEntity))
        .mapResp { resp =>
          resp.status shouldEqual StatusCodes.Created
        }

    }
    "fetch a tagged value" in {

      val expectedTag1 = jsonContentOf("/kg/resources/simple-resource-response.json",
                                       Map(quote("{priority}") -> "3", quote("{rev}") -> "2"))
      cl(Req(GET, s"$kgBase/resources/$id1/test-schema/test-resource:1?tag=v1.0.1", headersUser)).mapJson {
        (json, result) =>
          result.status shouldEqual StatusCodes.OK
          json.removeField("_createdAt").removeField("_updatedAt") shouldEqual expectedTag1
      }

      val expectedTag2 = jsonContentOf("/kg/resources/simple-resource-response.json",
                                       Map(quote("{priority}") -> "5", quote("{rev}") -> "1"))
      cl(Req(GET, s"$kgBase/resources/$id1/test-schema/test-resource:1?tag=v1.0.0", headersUser)).mapJson {
        (json, result) =>
          result.status shouldEqual StatusCodes.OK
          json.removeField("_createdAt").removeField("_updatedAt") shouldEqual expectedTag2
      }
    }
  }

  "listing resources" should {
    "add more resource to the project" in {

      forAll(2 to 5) { resourceId =>
        val payload = jsonContentOf("/kg/resources/simple-resource.json",
                                    Map(quote("{priority}") -> "3", quote("{resourceId}") -> s"$resourceId"))
        cl(Req(PUT, s"$kgBase/resources/$id1/test-schema/test-resource:$resourceId", headersUser, payload.toEntity))
          .mapResp { result =>
            result.status shouldEqual StatusCodes.Created
          }
      }

    }
    "list the resources" in {
      val expected = jsonContentOf("/kg/listings/response.json", Map(quote("{proj}") -> id1))
      eventually {
        cl(Req(GET, s"$kgBase/resources/$id1/test-schema", headersUser)).mapJson { (json, result) =>
          result.status shouldEqual StatusCodes.OK
          json.removeField("_createdAt").removeField("_updatedAt") shouldEqual expected
        }
      }
    }
  }

  private def kgReqEntity(path: String = "/kg/projects/project.json",
                          name: String = genString(),
                          base: String = s"${config.kg.uri.toString()}/resources/${genString()}/"): RequestEntity = {
    val rep = Map(quote("{name}") -> name, quote("{base}") -> base)
    jsonContentOf(path, rep).toEntity
  }

}