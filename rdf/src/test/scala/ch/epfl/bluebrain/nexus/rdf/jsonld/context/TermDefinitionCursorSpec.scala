//package ch.epfl.bluebrain.nexus.rdf.jsonld.context
//
//import ch.epfl.bluebrain.nexus.rdf.RdfSpec
//import ch.epfl.bluebrain.nexus.rdf.Vocabulary.xsd
//import ch.epfl.bluebrain.nexus.rdf.jsonld.NoneNullOr.Val
//import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinition.{ExpandedTermDefinition, SimpleTermDefinition}
//import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._
//import ch.epfl.bluebrain.nexus.rdf.syntax.all._
//class TermDefinitionCursorSpec extends RdfSpec {
//
//  "A TermDefinitionCursor" when {
//
//    val Foo = uri"http://example/Foo"
//    val bar = uri"http://example/bar"
//
//    "having single scoped context" should {
//
//      val json = jsonContentOf("/jsonld/expand/c007-in.jsonld")
//      val ctx  = json.hcursor.as[ContextWrapper].rightValue.`@context`
//      val c    = TermDefinitionCursor.fromCtx(ctx)
//
//      "navigate to definition" in {
//        c.down("a").downOrTop(Foo).down("bar").value shouldEqual
//          Val(ExpandedTermDefinition(bar, tpe = Some(id)))
//
//        c.down("a").downOrTop("bar").value shouldEqual
//          Val(ExpandedTermDefinition(bar, tpe = Some(xsd.string)))
//
//        c.down("a").down("some").downOrTop("bar").value shouldEqual
//          Val(ExpandedTermDefinition(bar, tpe = Some(xsd.string)))
//      }
//
//      "navigate to definition and merge contexts" in {
//        val barDefinition = ExpandedTermDefinition(bar, tpe = Some(id))
//        val ctxTerms = Map(
//          "Foo" -> ExpandedTermDefinition(Foo, context = Val(Context(Map("bar" -> barDefinition)))),
//          "bar" -> barDefinition
//        )
//
//        c.down("a").downOrTop(Foo).down("bar").valueResolved shouldEqual
//          Val(barDefinition.withContext(Val(Context(terms = ctxTerms, vocab = Some(uri"http://example/")))))
//      }
//    }
//
//    "having multiple scoped contexts" should {
//
//      val json = jsonContentOf("/jsonld/expand/c012-in.jsonld")
//      val ctx  = json.hcursor.as[ContextWrapper].rightValue.`@context`
//      val c    = TermDefinitionCursor.fromCtx(ctx)
//      val baz  = uri"http://example/baz"
//
//      "navigate to definition" in {
//        c.down(Foo).downOrTop("bar").downOrTop("baz").value shouldEqual
//          Val(ExpandedTermDefinition(baz, tpe = Some(vocab)))
//        c.down(Foo).downOrTop("not").down("baz").or(c).context shouldEqual ctx
//      }
//    }
//
//    "having single scoped context and an inner context" should {
//
//      val json     = jsonContentOf("/jsonld/expand/c010-in.jsonld")
//      val ctx      = json.hcursor.as[ContextWrapper].rightValue.`@context`
//      val innerCtx = json.hcursor.downField("a").as[ContextWrapper].rightValue.`@context`
//      val B        = uri"http://example/B"
//      val c        = TermDefinitionCursor.fromCtx(ctx)
//
//      "navigate to definition" in {
//        val cursorA = c.down("a").mergeContext(innerCtx)
//        cursorA.downOrTop(B).downOrTop("a").or(cursorA).context shouldEqual innerCtx
//        cursorA.downOrTop(B).downOrTop("c").or(cursorA).value shouldEqual
//          Val(SimpleTermDefinition(uri"http://example.org/c"))
//      }
//    }
//  }
//
//}
