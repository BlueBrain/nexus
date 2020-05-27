package ch.epfl.bluebrain.nexus.rdf

import java.time.{Instant, Period}
import java.util.UUID
import java.util.concurrent.TimeUnit

import cats.{Applicative, FlatMap, Functor, MonadError, SemigroupK}
import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, Url, Urn}
import ch.epfl.bluebrain.nexus.rdf.Node.{BNode, IriNode, IriOrBNode, Literal}
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.xsd
import ch.epfl.bluebrain.nexus.rdf.syntax.all._

import scala.concurrent.duration.{Duration, FiniteDuration}

class GraphDecoderSpec extends RdfSpec {

  private val id    = url"http://example.com/id"
  private val model = toJenaModel(jsonWithContext("/rdf/decoder.json"))
  private val graph = fromJenaModel(id, model)
  private val c     = graph.cursor

  "A Decoder" should {
    "successfully decode primitive types" in {
      c.down(nxv"boolean").as[Boolean].rightValue shouldEqual true
      c.down(nxv"int").as[Byte].rightValue shouldEqual 1
      c.down(nxv"int").as[Int].rightValue shouldEqual 1
      c.down(nxv"int").as[Short].rightValue shouldEqual 1.toShort
      c.down(nxv"long").as[Long].rightValue shouldEqual 1L
      c.down(nxv"float").as[Float].rightValue shouldEqual 1.2f
      c.down(nxv"double").as[Double].rightValue shouldEqual 1.2d
      c.down(nxv"string").as[String].rightValue shouldEqual "some string"
      c.down(nxv"string").as[Set[String]].rightValue shouldEqual Set("some string")
    }
    "successfully decode standard types" in {
      c.down(nxv"uuid").as[UUID].rightValue.toString shouldEqual "3aa14a1a-81e7-4147-8306-136d8270bb01"
      c.down(nxv"uuid").as[Some[UUID]].rightValue.value.toString shouldEqual "3aa14a1a-81e7-4147-8306-136d8270bb01"
      c.down(nxv"uuid").as[Option[UUID]].rightValue.value.toString shouldEqual "3aa14a1a-81e7-4147-8306-136d8270bb01"
      c.down(nxv"unknownPredicate").as[Option[UUID]].rightValue shouldEqual None
      c.down(nxv"list").as[List[Int]].rightValue shouldEqual List(1, 2, 2)
      c.down(nxv"list").as[Vector[Int]].rightValue shouldEqual Vector(1, 2, 2)
      c.down(nxv"list").as[Array[Int]].rightValue shouldEqual Vector(1, 2, 2)
      c.downSet(nxv"set").as[Set[Int]].rightValue should contain theSameElementsAs Set(1, 2)
      c.down(nxv"duration").as[Duration].rightValue shouldEqual Duration.Inf
      c.down(nxv"finiteDuration").as[FiniteDuration].rightValue shouldEqual FiniteDuration(3, TimeUnit.MINUTES)
      c.down(nxv"notFound").as[None.type].rightValue
      c.down(nxv"instant").as[Instant].rightValue.toString shouldEqual "2019-05-29T09:09:22.754Z"
      c.down(nxv"period").as[Period].rightValue.toString shouldEqual "P2Y3M4D"
      c.down(nxv"int").as[Either[Int, Boolean]].rightValue shouldEqual Left(1)
      c.down(nxv"int").as[Either[Boolean, Int]].rightValue shouldEqual Right(1)
    }
    "successfully decode rdf types" in {
      c.as[AbsoluteIri].rightValue shouldEqual id
      c.down(nxv"url").as[AbsoluteIri].rightValue shouldEqual id
      c.down(nxv"idUrl").as[AbsoluteIri].rightValue shouldEqual id
      c.down(nxv"url").as[Url].rightValue shouldEqual id
      c.down(nxv"idUrl").as[Url].rightValue shouldEqual id
      c.down(nxv"urn").as[Urn].rightValue shouldEqual urn"urn:uuid:3aa14a1a-81e7-4147-8306-136d8270bb01"
      c.down(nxv"idUrn").as[Urn].rightValue shouldEqual urn"urn:uuid:3aa14a1a-81e7-4147-8306-136d8270bb01"
      c.as[Cursor].rightValue shouldEqual c
      c.as[Node].rightValue shouldEqual IriNode(id)
      c.as[IriNode].rightValue shouldEqual IriNode(id)
      c.as[IriOrBNode].rightValue shouldEqual IriNode(id)
      c.down(nxv"string").as[Literal].rightValue.lexicalForm shouldEqual "some string"
      c.down(nxv"bnode").as[BNode].rightValue
    }
    "fail to decode" in {
      c.as[Int].leftValue
      c.as[Some[Int]].leftValue
      c.as[Float].leftValue
      c.as[Double].leftValue
      c.as[Long].leftValue
      c.as[UUID].leftValue
      c.as[List[Int]].leftValue
      c.as[List[String]].leftValue
      c.as[Set[Int]].leftValue
      c.as[Set[String]].leftValue
      c.down(nxv"float").as[None.type].leftValue
      c.down(nxv"float").as[Int].leftValue
      c.down(nxv"float").as[String].leftValue
      c.down(nxv"float").as[AbsoluteIri].leftValue
      c.down(nxv"float").as[Url].leftValue
      c.down(nxv"idUrn").as[Url].leftValue
      c.down(nxv"notFound").as[Url].leftValue
      c.down(nxv"float").as[Urn].leftValue
      c.down(nxv"idUrl").as[Urn].leftValue
      c.down(nxv"notFound").as[Urn].leftValue
      c.down(nxv"float").as[List[Float]].leftValue
      c.down(nxv"float").as[Vector[Float]].leftValue
      c.down(nxv"string").as[UUID].leftValue
      c.down(nxv"float").as[Instant].leftValue
      c.down(nxv"string").as[Instant].leftValue
      c.down(nxv"string").as[Either[Int, Boolean]].leftValue
      c.down(nxv"float").as[Period].leftValue
      c.down(nxv"string").as[Period].leftValue
      c.down(nxv"float").as[Duration].leftValue
      c.down(nxv"string").as[Duration].leftValue
      c.down(nxv"duration").as[FiniteDuration].leftValue
      c.down(nxv"notFound").as[BNode].leftValue
      c.down(nxv"notFound").as[IriNode].leftValue
      c.down(nxv"notFound").as[IriOrBNode].leftValue
      c.down(nxv"notFound").as[Literal].leftValue
      c.down(nxv"notFound").as[Node].leftValue
      Graph(Literal("", xsd.boolean)).cursor.as[Boolean].leftValue // illegal typed boolean
    }
    "or" in {
      val withDefault: GraphDecoder[String] =
        GraphDecoder.graphDecodeString or GraphDecoder.instance(_ => Right("default"))
      c.down(nxv"string").as[String](withDefault).rightValue shouldEqual "some string"
      c.down(nxv"int").as[String](withDefault).rightValue shouldEqual "default"
    }
    "combineK" in {
      val withDefault =
        SemigroupK[GraphDecoder].combineK(GraphDecoder.graphDecodeString, GraphDecoder.instance(_ => Right("default")))
      c.down(nxv"string").as[String](withDefault).rightValue shouldEqual "some string"
      c.down(nxv"int").as[String](withDefault).rightValue shouldEqual "default"
    }
    "const" in {
      Applicative[GraphDecoder].pure(1).apply(c).rightValue shouldEqual 1
    }
    "map" in {
      Functor[GraphDecoder]
        .map(GraphDecoder.graphDecodeString)(_.head)
        .apply(c.down(nxv"string"))
        .rightValue shouldEqual 's'
    }
    "flatMap" in {
      implicit val customUrnDecoder: GraphDecoder[Urn] =
        FlatMap[GraphDecoder].flatMap(GraphDecoder.graphDecodeString) { str =>
          if (str.startsWith("urn:")) GraphDecoder.graphDecodeUrn
          else GraphDecoder.failed(DecodingError("String does not start with 'urn:'", Nil))
        }
      c.down(nxv"urn").as[Urn].rightValue
      c.down(nxv"string").as[Urn].leftValue
      c.down(nxv"int").as[Urn].leftValue
    }
    "product" in {
      implicit val intShort: GraphDecoder[(Int, Short)] =
        Applicative[GraphDecoder].product(GraphDecoder.graphDecodeInt, GraphDecoder.graphDecodeShort)
      c.down(nxv"int").as[(Int, Short)].rightValue shouldEqual (1 -> 1.toShort)
    }
    "raiseError" in {
      val expected = DecodingError("msg", Nil)
      MonadError[GraphDecoder, DecodingError]
        .raiseError(expected)
        .apply(c.down(nxv"int"))
        .leftValue shouldEqual expected
    }
    "handleError" in {
      val failed  = GraphDecoder.failed[Int](DecodingError("msg", Nil))
      val handled = MonadError[GraphDecoder, DecodingError].handleErrorWith(failed) { _ => GraphDecoder.const(1) }
      handled(c.down(nxv"string")).rightValue shouldEqual 1
    }
    "return left for DecodingError in tailrecM" in {
      val failed  = GraphDecoder.failed[Either[Int, String]](DecodingError("fail", Nil))
      val decoder = MonadError[GraphDecoder, DecodingError].tailRecM(1)(_ => failed)
      decoder(c).leftValue
    }
    "return right for successful tailrecM" in {
      val success = GraphDecoder.const[Either[Int, String]](Right("success"))
      val decoder = MonadError[GraphDecoder, DecodingError].tailRecM(1)(_ => success)
      decoder(c).rightValue shouldEqual "success"
    }
    "recurse until right in tailrecM" in {
      val succeedsThirdTime: Int => GraphDecoder[Either[Int, String]] =
        int => GraphDecoder.instance { _ => if (int == 3) Right(Right("success")) else Right(Left(int + 1)) }
      val decoder = MonadError[GraphDecoder, DecodingError].tailRecM(1)(int => succeedsThirdTime(int))
      decoder(c).rightValue shouldEqual "success"
    }
  }

}
