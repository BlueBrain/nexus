package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{schema, xsd}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import io.circe.Json
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class JsonLdContextSpec extends AnyWordSpecLike with Matchers with Fixtures with Inspectors {

  "A Json-LD context" should {
    val context = jsonContentOf(s"/jsonld/context/context.json")
    val api     = implicitly[JsonLdApi]

    "be constructed successfully skipping fields inspection" in {
      api.context(context, ContextFields.Skip).runSyncUnsafe() shouldBe a[RawJsonLdContext]
    }

    "be constructed successfully including fields inspection" in {
      api.context(context, ContextFields.Include).runSyncUnsafe() shouldBe a[ExtendedJsonLdContext]
    }

    "get fields" in {
      val result = api.context(context, ContextFields.Include).runSyncUnsafe()
      result.value shouldEqual context.topContextValueOrEmpty
      result.base.value shouldEqual base.value
      result.vocab.value shouldEqual vocab.value
      result.aliases shouldEqual
        Map(
          "Person"     -> (schema + "Person"),
          "deprecated" -> (schema + "deprecated"),
          "customid"   -> (vocab + "customid")
        )
      result.prefixMappings shouldEqual Map("schema" -> schema.base, "xsd" -> xsd.base)
    }

    "add simple alias with ContextFields.Include" in {
      val context = json"""{"@context": {"@base": "${base.value}"} }"""
      val result  = api.context(context, ContextFields.Include).runSyncUnsafe()
      result.addAlias("age", schema.age) shouldEqual ExtendedJsonLdContext(
        value = json"""{"@base": "${base.value}", "age": "${schema.age}"}""",
        base = Some(base.value),
        aliases = Map("age" -> schema.age)
      )
    }

    "add simple alias with ContextFields.Skip" in {
      val context = json"""{"@context": {"@base": "${base.value}"} }"""
      val result  = api.context(context, ContextFields.Skip).runSyncUnsafe()
      result.addAlias("age", schema.age) shouldEqual
        RawJsonLdContext(json"""{"@base": "${base.value}", "age": "${schema.age}"}""")
    }

    "add alias with dataType and ContextFields.Include" in {
      val context = json"""{"@context": {"@base": "${base.value}"} }"""
      val result  = api.context(context, ContextFields.Include).runSyncUnsafe()
      result.addAlias("age", schema.age, xsd.integer) shouldEqual ExtendedJsonLdContext(
        value = json"""{"@base": "${base.value}", "age": {"@type": "${xsd.integer}", "@id": "${schema.age}"}}""",
        base = Some(base.value),
        aliases = Map("age" -> schema.age)
      )
    }

    "add alias with dataType and ContextFields.Skip" in {
      val context = json"""{"@context": {"@base": "${base.value}"} }"""
      val result  = api.context(context, ContextFields.Skip).runSyncUnsafe()
      result.addAlias("age", schema.age, xsd.integer) shouldEqual
        RawJsonLdContext(
          json"""{"@base": "${base.value}", "age": {"@type": "${xsd.integer}", "@id": "${schema.age}"}}"""
        )
    }

    "add alias with @type @id and ContextFields.Include" in {
      val context = json"""{"@context": {"@base": "${base.value}"} }"""
      val result  = api.context(context, ContextFields.Include).runSyncUnsafe()
      result.addAliasIdType("unit", schema.unitText) shouldEqual ExtendedJsonLdContext(
        value = json"""{"@base": "${base.value}", "unit": {"@type": "@id", "@id": "${schema.unitText}"}}""",
        base = Some(base.value),
        aliases = Map("unit" -> schema.unitText)
      )
    }

    "add alias with @type @id and ContextFields.Skip" in {
      val context = json"""{"@context": {"@base": "${base.value}"} }"""
      val result  = api.context(context, ContextFields.Skip).runSyncUnsafe()
      result.addAliasIdType("unit", schema.unitText) shouldEqual
        RawJsonLdContext(json"""{"@base": "${base.value}", "unit": {"@type": "@id", "@id": "${schema.unitText}"}}""")
    }

    "add prefixMapping with ContextFields.Include" in {
      val context = json"""{"@context": [{"@base": "${base.value}"}] }"""
      val result  = api.context(context, ContextFields.Include).runSyncUnsafe()
      result.addPrefix("xsd", xsd.base) shouldEqual ExtendedJsonLdContext(
        value = json"""[{"@base": "${base.value}"}, {"xsd": "${xsd.base}"}]""",
        base = Some(base.value),
        prefixMappings = Map("xsd" -> xsd.base)
      )
    }

    "add prefixMapping with ContextFields.Skip" in {
      val context = json"""{"@context": [{"@base": "${base.value}"}] }"""
      val result  = api.context(context, ContextFields.Skip).runSyncUnsafe()
      result.addPrefix("xsd", xsd.base) shouldEqual
        RawJsonLdContext(json"""[{"@base": "${base.value}"}, {"xsd": "${xsd.base}"}]""")
    }

    "add remote contexts IRI" in {
      val remoteCtx = iri"http://example.com/remote"
      val list      = List(
        json"""{"@id":"$iri","age": 30}"""                                                                                    -> json"""{"@context":"$remoteCtx","@id":"$iri","age": 30}""",
        json"""{"@context":"http://ex.com/1","@id":"$iri","age": 30}"""                                                       -> json"""{"@context":["http://ex.com/1","$remoteCtx"],"@id":"$iri","age": 30}""",
        json"""{"@context":[],"@id":"$iri","age": 30}"""                                                                      -> json"""{"@context":"$remoteCtx","@id":"$iri","age": 30}""",
        json"""{"@context":{},"@id":"$iri","age": 30}"""                                                                      -> json"""{"@context":"$remoteCtx","@id":"$iri","age": 30}""",
        json"""{"@context":"","@id":"$iri","age": 30}"""                                                                      -> json"""{"@context":"$remoteCtx","@id":"$iri","age": 30}""",
        json"""{"@context":["http://ex.com/1","http://ex.com/2"],"@id":"$iri","age": 30}"""                                   -> json"""{"@context":["http://ex.com/1","http://ex.com/2","$remoteCtx"],"@id":"$iri","age": 30}""",
        json"""{"@context":{"@vocab":"${vocab.value}","@base":"${base.value}"},"@id":"$iri","age": 30}"""                     -> json"""{"@context":[{"@vocab":"${vocab.value}","@base":"${base.value}"},"$remoteCtx"],"@id":"$iri","age": 30}""",
        json"""{"@context":[{"@vocab":"${vocab.value}","@base":"${base.value}"},"http://ex.com/1"],"@id":"$iri","age": 30}""" -> json"""{"@context":[{"@vocab":"${vocab.value}","@base":"${base.value}"}, "http://ex.com/1", "$remoteCtx"],"@id":"$iri","age": 30}"""
      )
      forAll(list) {
        case (input, expected) =>
          input.addContext(remoteCtx) shouldEqual expected
      }
    }

    "merge contexts" in {
      val context1 = jsonContentOf("/jsonld/context/context1.json")
      val context2 = jsonContentOf("/jsonld/context/context2.json")
      val expected = jsonContentOf("/jsonld/context/context12-merged.json")
      context1.addContext(context2) shouldEqual expected

      val json1 = context1 deepMerge json"""{"@id": "$iri", "age": 30}"""
      json1.addContext(context2) shouldEqual expected.deepMerge(json"""{"@id": "$iri", "age": 30}""")
    }

    "merge contexts when one is empty" in {
      val context1    = jsonContentOf("/jsonld/context/context1.json")
      val emptyArrCtx = json"""{"@context": []}"""
      val emptyObjCtx = json"""{"@context": {}}"""
      context1.addContext(emptyArrCtx) shouldEqual context1
      context1.addContext(emptyObjCtx) shouldEqual context1
    }

    "merge remote context IRIs" in {
      val remoteCtxIri1 = iri"http://example.com/remote1"
      val remoteCtxIri2 = iri"http://example.com/remote2"
      val remoteCtx1    = json"""{"@context": "$remoteCtxIri1"}"""
      val remoteCtx2    = json"""{"@context": "$remoteCtxIri2"}"""
      remoteCtx1.addContext(remoteCtx2) shouldEqual json"""{"@context": ["$remoteCtxIri1","$remoteCtxIri2"]}"""
    }

    "merge contexts with arrays" in {
      val context1Array = jsonContentOf("/jsonld/context/context1-array.json")
      val context2      = jsonContentOf("/jsonld/context/context2.json")
      val expected      = jsonContentOf("/jsonld/context/context12-merged-array.json")
      context1Array.addContext(context2) shouldEqual expected

      val json1 = context1Array deepMerge json"""{"@id": "$iri", "age": 30}"""
      json1.addContext(context2) shouldEqual expected.deepMerge(json"""{"@id": "$iri", "age": 30}""")
    }

    "fetch the @context value" in {
      json"""[{"@context": {"@base": "${base.value}"}, "@id": "$iri", "age": 30}]""".topContextValueOrEmpty shouldEqual json"""{"@base": "${base.value}"}"""
      json"""{"@context": {"@base": "${base.value}"}, "@id": "$iri", "age": 30}""".topContextValueOrEmpty shouldEqual json"""{"@base": "${base.value}"}"""
      json"""{"@id": "$iri", "age": 30}""".topContextValueOrEmpty shouldEqual Json.obj()
    }

  }
}
