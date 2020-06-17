package ch.epfl.bluebrain.nexus.kg.resources

import ch.epfl.bluebrain.nexus.kg.resources.Ref._
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.commons.test.EitherValues
import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, Urn}
import ch.epfl.bluebrain.nexus.rdf.implicits._
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class RefSpec extends AnyWordSpecLike with Matchers with Inspectors with EitherValues {

  "A Ref" should {

    "be constructed from an AbsoluteIri" in {
      val list = List[(AbsoluteIri, Ref)](
        url"http://ex.com?rev=1&other=value"             -> Revision(url"http://ex.com?other=value", 1L),
        url"http://ex.com?rev=1"                         -> Revision(url"http://ex.com", 1L),
        url"http://ex.com?tag=this&other=value"          -> Ref.Tag(url"http://ex.com?other=value", "this"),
        url"http://ex.com?rev=1&tag=this&other=value"    -> Revision(url"http://ex.com?other=value", 1L),
        url"http://ex.com?other=value"                   -> Latest(url"http://ex.com?other=value"),
        url"http://ex.com#fragment"                      -> Latest(url"http://ex.com#fragment"),
        Urn("urn:ex:a/b/c").rightValue                   -> Latest(Urn("urn:ex:a/b/c").rightValue),
        Urn("urn:ex:a/b/c?=rev=1").rightValue            -> Revision(Urn("urn:ex:a/b/c").rightValue, 1L),
        Urn("urn:ex:a?=tag=this&other=value").rightValue -> Ref.Tag(Urn("urn:ex:a?=other=value").rightValue, "this")
      )
      forAll(list) {
        case (iri, ref) => Ref(iri) shouldEqual ref
      }
    }

    "print properly" in {
      (Latest(url"http://ex.com#fragment"): Ref).show shouldEqual url"http://ex.com#fragment".show
      (Revision(
        url"http://ex.com?other=value",
        1L
      ): Ref).show shouldEqual url"http://ex.com?other=value".show + s" @ rev: '1'"
      (Ref.Tag(
        url"http://ex.com?other=value",
        "this"
      ): Ref).show shouldEqual url"http://ex.com?other=value".show + s" @ tag: 'this'"
    }
  }

}
