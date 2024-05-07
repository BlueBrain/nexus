package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.SerializerSuite.{Bar, Foo}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie._
import doobie.implicits._
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import munit.AnyFixture

class SerializerSuite extends NexusSuite with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  test("Check foo serializer not dropping nulls") {
    implicit val getValue: Get[Foo] = Foo.serializer.getValue
    implicit val putValue: Put[Foo] = Foo.serializer.putValue
    val foo                         = Foo(5, None)
    val foo2                        = Foo(10, Some(2))
    val insertGet                   = for {
      _              <- sql"""CREATE TEMPORARY table FOO (value JSONB NOT NULL)""".update.run
      _              <- sql"""INSERT INTO FOO values ($foo)""".update.run
      _              <- sql"""INSERT INTO FOO values ($foo2)""".update.run
      resultAsString <- sql"""SELECT value::text FROM FOO""".query[String].to[Set]
      resultAsFoo    <- sql"""SELECT value FROM FOO""".query[Foo].to[Set]
    } yield (resultAsString, resultAsFoo)

    // Jsonb formats the output so we get spaces back
    val expectedString = Set(
      """{"x": 5, "y": null}""",
      """{"x": 10, "y": 2}"""
    )

    insertGet.transact(xas.write).assertEquals(expectedString -> Set(foo, foo2))
  }

  test("Check bar serializer dropping nulls") {
    implicit val getValue: Get[Bar] = Bar.serializer.getValue
    implicit val putValue: Put[Bar] = Bar.serializer.putValue
    val bar                         = Bar(5, None)
    val bar2                        = Bar(10, Some(2))
    val insertGet                   = for {
      _              <- sql"""CREATE TEMPORARY table BAR (value JSONB NOT NULL)""".update.run
      _              <- sql"""INSERT INTO BAR values ($bar)""".update.run
      _              <- sql"""INSERT INTO BAR values ($bar2)""".update.run
      resultAsString <- sql"""SELECT value::text FROM BAR""".query[String].to[Set]
      resultAsBar    <- sql"""SELECT value FROM BAR""".query[Bar].to[Set]
    } yield (resultAsString, resultAsBar)

    // Jsonb formats the output so we get spaces back
    val expectedString = Set(
      """{"x": 5}""",
      """{"x": 10, "y": 2}"""
    )

    insertGet.transact(xas.write).assertEquals(expectedString -> Set(bar, bar2))
  }

}

object SerializerSuite {

  final case class Foo(x: Int, y: Option[Int])

  object Foo {

    val serializer: Serializer[Int, Foo] = {
      implicit val configuration: Configuration = Serializer.circeConfiguration

      implicit val coder: Codec.AsObject[Foo] = deriveConfiguredCodec[Foo]
      Serializer(i => iri"https://localhost/foo/$i")
    }
  }

  final case class Bar(x: Int, y: Option[Int])

  object Bar {

    val serializer: Serializer[Int, Bar] = {
      implicit val configuration: Configuration = Serializer.circeConfiguration

      implicit val coder: Codec.AsObject[Bar] = deriveConfiguredCodec[Bar]
      Serializer.dropNulls(i => iri"https://localhost/foo/$i")
    }
  }

}
