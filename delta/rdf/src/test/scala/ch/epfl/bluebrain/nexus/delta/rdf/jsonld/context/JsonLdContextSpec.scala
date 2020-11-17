package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{rdf, schema, xsd}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class JsonLdContextSpec extends AnyWordSpecLike with Matchers with Fixtures with Inspectors {

  "A Json-LD context" should {
    val context = jsonContentOf("context.json").topContextValueOrEmpty
    val api     = implicitly[JsonLdApi]

    "be constructed successfully" in {
      api.context(context).accepted shouldBe a[JsonLdContext]
    }

    "get fields" in {
      val result = api.context(context).accepted
      result.value shouldEqual context
      result.base.value shouldEqual base.value
      result.vocab.value shouldEqual vocab.value
      result.aliases shouldEqual
        Map(
          "Person"     -> schema.Person,
          "Person2"    -> schema.Person,
          "deprecated" -> (schema + "deprecated"),
          "customid"   -> (vocab + "customid")
        )
      result.aliasesInv shouldEqual
        Map(
          schema.Person           -> "Person",
          (schema + "deprecated") -> "deprecated",
          (vocab + "customid")    -> "customid"
        )
      result.prefixMappings shouldEqual Map("schema" -> schema.base, "xsd" -> xsd.base, "xsd2" -> xsd.base)
      result.prefixMappingsInv shouldEqual Map(schema.base -> "schema", xsd.base -> "xsd")
    }

    "compact an iri to its short form using an alias" in {
      val result = api.context(context).accepted
      result.alias(schema.Person).value shouldEqual "Person"
      result.alias(schema.age) shouldEqual None
    }

    "compact an iri to a CURIE using a prefix mappings" in {
      val result = api.context(context).accepted
      result.curie(schema.age).value shouldEqual "schema:age"
      result.curie(rdf.tpe) shouldEqual None
    }

    "compact an iri to its short form using the vocab" in {
      val result = api.context(context).accepted
      result.compactVocab(vocab + "name").value shouldEqual "name"
      result.compactVocab(schema.age) shouldEqual None
    }

    "compact an iri to its short form using the base" in {
      val result = api.context(context).accepted
      result.compactBase(base + "name").value shouldEqual "name"
      result.compactBase(schema.age) shouldEqual None
    }

    "compact an iri using aliases, vocab and prefix mappings" in {
      val result = api.context(context).accepted
      val list   = List(
        vocab + "name" -> "name",
        schema.Person  -> "Person",
        schema.age     -> "schema:age",
        rdf.tpe        -> rdf.tpe.toString
      )

      forAll(list) { case (iri, expected) =>
        result.compact(iri, useVocab = true) shouldEqual expected
      }
    }

    "compact an iri using aliases, base and prefix mappings" in {
      val result = api.context(context).accepted
      val list   = List(
        base + "name" -> "name",
        schema.Person -> "Person",
        schema.age    -> "schema:age",
        rdf.tpe       -> rdf.tpe.toString
      )

      forAll(list) { case (iri, expected) =>
        result.compact(iri, useVocab = false) shouldEqual expected
      }
    }

    "add simple alias" in {
      val context = json"""{"@context": {"@base": "${base.value}"} }""".topContextValueOrEmpty
      val result  = api.context(context).accepted
      result.addAlias("age", schema.age) shouldEqual JsonLdContext(
        value = ContextValue(json"""{"@base": "${base.value}", "age": "${schema.age}"}"""),
        base = Some(base.value),
        aliases = Map("age" -> schema.age)
      )
    }

    "add alias with dataType" in {
      val context = json"""{"@context": {"@base": "${base.value}"} }""".topContextValueOrEmpty
      val result  = api.context(context).accepted
      result.addAlias("age", schema.age, xsd.integer) shouldEqual JsonLdContext(
        value = ContextValue(
          json"""{"@base": "${base.value}", "age": {"@type": "${xsd.integer}", "@id": "${schema.age}"}}"""
        ),
        base = Some(base.value),
        aliases = Map("age" -> schema.age)
      )
    }

    "add alias with @type @id" in {
      val context = json"""{"@context": {"@base": "${base.value}"} }""".topContextValueOrEmpty
      val result  = api.context(context).accepted
      result.addAliasIdType("unit", schema.unitText) shouldEqual JsonLdContext(
        value =
          ContextValue(json"""{"@base": "${base.value}", "unit": {"@type": "@id", "@id": "${schema.unitText}"}}"""),
        base = Some(base.value),
        aliases = Map("unit" -> schema.unitText)
      )
    }

    "add prefixMapping" in {
      val context = json"""{"@context": [{"@base": "${base.value}"}] }""".topContextValueOrEmpty
      val result  = api.context(context).accepted
      result.addPrefix("xsd", xsd.base) shouldEqual JsonLdContext(
        value = ContextValue(json"""[{"@base": "${base.value}"}, {"xsd": "${xsd.base}"}]"""),
        base = Some(base.value),
        prefixMappings = Map("xsd" -> xsd.base)
      )
    }

    "add remote contexts Iri" in {
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
      forAll(list) { case (input, expected) =>
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

  }
}
