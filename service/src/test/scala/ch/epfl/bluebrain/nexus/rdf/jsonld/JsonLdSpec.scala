package ch.epfl.bluebrain.nexus.rdf.jsonld

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Node.BNode
import ch.epfl.bluebrain.nexus.rdf.{Graph, RdfSpec}
import ch.epfl.bluebrain.nexus.rdf.jsonld.JsonLd._
import ch.epfl.bluebrain.nexus.rdf.jsonld.syntax._
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import io.circe.Json
import io.circe.literal._
import io.circe.syntax._

import scala.util.{Failure, Success, Try}

class JsonLdSpec extends RdfSpec {

  "JsonLd" should {
    "resolve contexts" when {
      "no @context is present" in {
        val json = json"""{"someKey": "value"}"""

        json
          .resolveContext[Try](_ => Failure(new IllegalArgumentException))
          .success
          .value
          .rightValue shouldEqual
          json"""
          {
            "@context": {},
            "someKey": "value"
          }
          """
      }

      "context is an IRI" in {
        val contextUri = url"http://context.example.com"

        val json =
          json"""
          {
            "@context": ${contextUri.asString}
          }
          """

        val contextValue =
          json"""
          {
            "@context": {
              "schema": "http://schema.org/"
            }
          }       
          """

        json
          .resolveContext[Try] {
            case `contextUri` => Success(Some(contextValue))
            case _            => Failure(new IllegalArgumentException)
          }
          .success
          .value
          .rightValue shouldEqual (json deepMerge contextValue)
      }

      "context is a JSON array containing IRI" in {
        val contextUri = url"http://context.example.com"

        val json =
          json"""
          {
            "@context": [
              {
                "xsd": "http://www.w3.org/2001/XMLSchema#"
              },
              ${contextUri.asString}
            ]
          }                
          """

        val contextValue =
          json"""
          {
            "@context": {
              "schema": "http://schema.org/"
            }
          }       
          """

        val expected =
          json"""
          {
            "@context": {
              "xsd": "http://www.w3.org/2001/XMLSchema#",
              "schema": "http://schema.org/"
            }
          }               
          """

        json
          .resolveContext[Try] {
            case `contextUri` => Success(Some(contextValue))
            case _            => Failure(new IllegalArgumentException)
          }
          .success
          .value
          .rightValue shouldEqual expected
      }
      "contexts are nested" in {
        val contextUri1 = url"http://context1.example.com"
        val contextUri2 = url"http://context2.example.com"
        val json =
          json"""
          {
            "@context": [
              {
                "xsd": "http://www.w3.org/2001/XMLSchema#"
              },
              ${contextUri1.asString}
            ]
          }                
          """

        val contextValue1 =
          json"""
          {
            "@context": [
              {
                "schema": "http://schema.org/"
              },
              ${contextUri2.asString}
            ]
          }                
          """

        val contextValue2 =
          json"""
          {
            "@context": {
              "nxv": "https://bluebrain.github.io/nexus/vocabulary/"
            }
          }       
          """

        val expected =
          json"""
          {
            "@context" : {
              "xsd": "http://www.w3.org/2001/XMLSchema#",
              "schema": "http://schema.org/",
              "nxv": "https://bluebrain.github.io/nexus/vocabulary/"
            }
          }
          """

        json
          .resolveContext[Try] {
            case `contextUri1` => Success(Some(contextValue1))
            case `contextUri2` => Success(Some(contextValue2))
            case _             => Failure(new IllegalArgumentException)
          }
          .success
          .value
          .rightValue shouldEqual expected
      }

      "there are scoped contexts" in {
        val contextUri = url"http://context.example.com"
        val json =
          json"""
           {
             "@context": {
               "xsd": "http://www.default-xsd.com#"
             },
             "key": {
               "@context": {
                 "xsd": "http://www.w3.org/2001/XMLSchema#"
               },
               "key-value1": "value1",
               "key2": {
                 "@context": ${contextUri.asString},
                 "key-value2": "value2"
               }
             }
           }
           """

        val contextValue =
          json"""
          {
            "@context": {
              "schema": "http://schema.org/"
            }
          }
          """
        val expected =
          json"""
          {
            "@context": {
              "schema": "http://schema.org/",
              "xsd": "http://www.w3.org/2001/XMLSchema#"
            },
            "key": {
              "key-value1": "value1",
              "key2": {
                "key-value2": "value2"
              }
            }
          }
          """

        json
          .resolveContext[Try] {
            case `contextUri` => Success(Some(contextValue))
            case _            => Failure(new IllegalArgumentException)
          }
          .success
          .value
          .rightValue shouldEqual expected
      }
    }

    "fail to resolve" when {
      "there are circular dependencies in contexts" in {
        val contextUri1 = url"http://context1.example.com"
        val contextUri2 = url"http://context2.example.com"
        val json =
          json"""
          {
            "@context": [
              {
                "xsd": "http://www.w3.org/2001/XMLSchema#"
              },
              ${contextUri1.asString}
            ]
          }                
          """

        val contextValue1 =
          json"""
          {
            "@context": [
              {
                "schema": "http://schema.org/"
              },
              ${contextUri2.asString}
            ]
          }                
          """

        val contextValue2 =
          json"""
          {
            "@context": ${contextUri1.asString}
          }       
          """

        json
          .resolveContext[Try] {
            case `contextUri1` => Success(Some(contextValue1))
            case `contextUri2` => Success(Some(contextValue2))
            case _             => Failure(new IllegalArgumentException)
          }
          .success
          .value
          .leftValue shouldEqual CircularContextDependency(List(contextUri1, contextUri2, contextUri1))
      }

      "context reference is not a string" in {
        val json =
          json"""
          {
            "@context": 1
          }
          """

        json
          .resolveContext[Try](_ => Failure(new IllegalArgumentException))
          .success
          .value
          .leftValue shouldEqual IllegalContextValue("1")

      }
      "context reference is an invalid IRI" in {
        val json =
          json"""
          {
            "@context": "notAContext"
          }
          """

        json
          .resolveContext[Try](_ => Failure(new IllegalArgumentException))
          .success
          .value
          .leftValue shouldEqual IllegalContextValue("notAContext")

      }
      "context is not found" in {
        val contextUri = url"http://context.example.com"

        val json =
          json"""
          {
            "@context": ${contextUri.asString}
          }
          """
        json
          .resolveContext[Try] {
            case `contextUri` => Success(None)
            case _            => Failure(new IllegalArgumentException)
          }
          .success
          .value
          .leftValue shouldEqual ContextNotFound(contextUri)
      }
    }

    val context: AbsoluteIri = url"https://bbp-nexus.epfl.ch/dev/v0/contexts/bbp/core/context/v0.1.0"

    "injecting context" should {
      val contextString = Json.fromString(context.show)

      val mapping = List(
        Json.obj("@id"        -> Json.fromString("foo-id"), "nxv:rev" -> Json.fromLong(1)) ->
          Json.obj("@context" -> contextString, "@id"                 -> Json.fromString("foo-id"), "nxv:rev" -> Json.fromLong(1)),
        Json.obj(
          "@context" -> Json.fromString("http://foo.domain/some/context"),
          "@id"      -> Json.fromString("foo-id"),
          "nxv:rev"  -> Json.fromLong(1)
        ) ->
          Json.obj(
            "@context" -> Json.arr(Json.fromString("http://foo.domain/some/context"), contextString),
            "@id"      -> Json.fromString("foo-id"),
            "nxv:rev"  -> Json.fromLong(1)
          ),
        Json.obj(
          "@context" -> Json
            .arr(
              Json.fromString("http://foo.domain/some/context"),
              Json.fromString("http://bar.domain/another/context")
            ),
          "@id"     -> Json.fromString("foo-id"),
          "nxv:rev" -> Json.fromLong(1)
        ) ->
          Json.obj(
            "@context" -> Json.arr(
              Json.fromString("http://foo.domain/some/context"),
              Json.fromString("http://bar.domain/another/context"),
              contextString
            ),
            "@id"     -> Json.fromString("foo-id"),
            "nxv:rev" -> Json.fromLong(1)
          ),
        Json.obj(
          "@context" -> Json.obj(
            "foo" -> Json.fromString("http://foo.domain/some/context"),
            "bar" -> Json.fromString("http://bar.domain/another/context")
          ),
          "@id"     -> Json.fromString("foo-id"),
          "nxv:rev" -> Json.fromLong(1)
        ) ->
          Json.obj(
            "@context" -> Json.arr(
              Json.obj(
                "foo" -> Json.fromString("http://foo.domain/some/context"),
                "bar" -> Json.fromString("http://bar.domain/another/context")
              ),
              contextString
            ),
            "@id"     -> Json.fromString("foo-id"),
            "nxv:rev" -> Json.fromLong(1)
          )
      )

      "properly add or merge context into JSON payload" in {
        forAll(mapping) {
          case (in, out) =>
            in.addContext(context) shouldEqual out
        }
      }

      "added context uri to empty context value" in {
        val list = List(
          Json.obj("@context" -> Json.obj()).addContext(context),
          Json.obj("@context" -> Json.arr()).addContext(context),
          Json.obj("@context" -> Json.fromString("")).addContext(context)
        )
        forAll(list)(_ shouldEqual Json.obj("@context" -> Json.fromString(context.asString)))
      }

      "be idempotent" in {
        forAll(mapping) {
          case (in, _) =>
            in.addContext(context) shouldEqual in.addContext(context).addContext(context)
        }
      }

      "extract context" in {
        val context1          = jsonContentOf("/rdf/context/context1.json")
        val context1Extracted = jsonContentOf("/rdf/context/context1_extracted.json")
        context1.contextValue shouldEqual context1Extracted
      }

      "extract context with embedded contexts" in {
        val context1          = jsonContentOf("/rdf/context/embedded-context.json")
        val context1Extracted = jsonContentOf("/rdf/context/context1_extracted.json")
        context1.contextValue shouldEqual context1Extracted
      }

      "extract context with embedded IRI contexts" in {
        val context1 = jsonContentOf("/rdf/context/embedded-context-links.json")
        context1.contextValue shouldEqual
          Json.arr(
            "http://example.com/1".asJson,
            "http://example.com/2".asJson,
            Json.obj("xsd" -> "http://www.w3.org/2001/XMLSchema#".asJson),
            "http://example.com/3".asJson
          )
      }

      "extract empty json when @context key missing" in {
        Json.obj("one" -> Json.fromInt(1)).contextValue shouldEqual Json.obj()
      }

      "merge two contexts" in {
        val context1 = jsonContentOf("/rdf/context/context1.json")
        val context2 = jsonContentOf("/rdf/context/context2.json")

        context1 mergeContext context2 shouldEqual jsonContentOf("/rdf/context/context_merged.json")
      }

      "append context" in {
        val context1 = jsonContentOf("/rdf/context/context1.json")
        val json     = context1 deepMerge Json.obj("one" -> Json.fromInt(1), "two" -> Json.fromInt(2))
        val context2 = jsonContentOf("/rdf/context/context2.json")
        val json2    = context2 deepMerge Json.obj("three" -> Json.fromInt(3), "four" -> Json.fromInt(4))

        json appendContextOf json2 shouldEqual (jsonContentOf("/rdf/context/context_merged.json") deepMerge Json.obj(
          "one" -> Json.fromInt(1),
          "two" -> Json.fromInt(2)
        ))
      }

      "append context when array" in {
        val context1 = jsonContentOf("/rdf/context/context1-array.json")
        val json     = context1 deepMerge Json.obj("one" -> Json.fromInt(1), "two" -> Json.fromInt(2))
        val context2 = jsonContentOf("/rdf/context/context2.json")
        val json2    = context2 deepMerge Json.obj("three" -> Json.fromInt(3), "four" -> Json.fromInt(4))
        json appendContextOf json2 shouldEqual (jsonContentOf("/rdf/context/context_merged-array.json") deepMerge Json.obj(
          "one" -> Json.fromInt(1),
          "two" -> Json.fromInt(2)
        ))
      }

      "replace context" in {
        val context1 = jsonContentOf("/rdf/context/context1.json")
        val json     = context1 deepMerge Json.obj("one" -> Json.fromInt(1), "two" -> Json.fromInt(2))
        val context2 = Json.obj("@context" -> Json.obj("some" -> Json.fromString("context")))
        val json2    = context2 deepMerge Json.obj("three" -> Json.fromInt(3), "four" -> Json.fromInt(4))

        json replaceContext json2 shouldEqual
          (Json.obj("one" -> Json.fromInt(1), "two" -> Json.fromInt(2)) deepMerge context2)
      }

      "replace context with iri" in {
        val context1 = jsonContentOf("/rdf/context/context1.json")
        val json     = context1 deepMerge Json.obj("one" -> Json.fromInt(1), "two" -> Json.fromInt(2))

        json replaceContext context shouldEqual
          Json.obj("@context" -> Json.fromString(context.asString), "one" -> Json.fromInt(1), "two" -> Json.fromInt(2))
      }
    }

    "manipulating json" should {
      "remove keys object" in {
        val obj = Json.obj("one" -> Json.obj("two" -> Json.fromString("something")), "two" -> Json.fromString("abc"))
        obj.removeKeys("two") shouldEqual Json.obj("one" -> Json.obj("two" -> Json.fromString("something")))
        obj.removeKeys("three") shouldEqual obj
      }

      "remove the key from the json on nested fields" in {
        val json     = jsonContentOf("/rdf/context/embedded-context-links.json")
        val expected = jsonContentOf("/rdf/context/embedded-links-without-context.json")
        json.removeNestedKeys("@context") shouldEqual expected
      }

      "remove keys array" in {
        val obj      = Json.obj("one" -> Json.obj("two" -> Json.fromString("something")), "two" -> Json.fromString("abc"))
        val objNoKey = Json.obj("one" -> Json.obj("two" -> Json.fromString("something")))
        val arrObj   = Json.arr(obj, obj, Json.obj("three" -> Json.fromString("something")))
        arrObj.removeKeys("two") shouldEqual Json.arr(
          objNoKey,
          objNoKey,
          Json.obj("three" -> Json.fromString("something"))
        )
      }

      "fetch @id aliases" in {
        val json = jsonContentOf("/rdf/context/context-aliases.json")
        json.contextAliases("@id") shouldEqual Set("id1", "id2")
        json.contextAliases("@type") shouldEqual Set.empty[String]
      }

      "add @id" in {
        val json = Json.obj("key" -> Json.fromString("value"))
        val id   = url"http://example.com/id"
        json.id(id) shouldEqual
          Json.obj("key" -> Json.fromString("value"), "@id" -> Json.fromString(id.asString))

        val jsonArr = Json.arr(json)
        jsonArr.id(id) shouldEqual
          Json.arr(Json.obj("key" -> Json.fromString("value"), "@id" -> Json.fromString(id.asString)))

      }

      "find top level @id in Json" in {
        jsonContentOf("/rdf/context/simple-iri-context.json").id.rightValue shouldEqual url"http://nexus.example.com/john-doé"
      }
    }

    "convert graph to JSON" when {

      "root node is an IriNode" in {

        val rootNode = url"http://exampe.com/rootId"
        val property = url"http://example.com/property"

        val context =
          json"""
          {
            "@context": {
              "property": "http://example.com/property"
            },
            "@id": "http://exampe.com/contextId"
          }
          """

        val graph = Graph(rootNode, Set((rootNode, property, "value")))

        val expected =
          json"""
          {
             "@context": {
              "property": "http://example.com/property"
            },
            "@id": "http://exampe.com/rootId",
            "property": "value"
          }
          """

        graph.toJson(context).rightValue shouldEqual expected
      }

      "root node is a BNode" in {

        val rootNode = BNode()
        val property = url"http://example.com/property"

        val context =
          json"""
          {
            "@context": {
              "property": "http://example.com/property"
            },
          "@id": "http://exampe.com/contextId"
          }
          """

        val graph = Graph(rootNode, Set((rootNode, property, "value")))

        val expected =
          json"""
          {
            "@context": {
              "property": "http://example.com/property"
            },
            "property": "value"
          }
          """

        graph.toJson(context).rightValue shouldEqual expected
      }

      "root node is an IRINode and the graph is empty" in {

        val graph = Graph(url"http://exampe.com/rootId")

        val expected =
          json"""
          {
            "@id": "http://exampe.com/rootId"
          }
          """

        graph.toJson().rightValue shouldEqual expected
      }

      "root node is a BNode and the graph is empty" in {
        val graph    = Graph(BNode())
        val expected = Json.obj()

        graph.toJson().rightValue shouldEqual expected
      }

    }
  }
}
