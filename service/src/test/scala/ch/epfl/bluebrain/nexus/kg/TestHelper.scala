package ch.epfl.bluebrain.nexus.kg

import java.time.Clock
import java.util.UUID

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.commons.test.{EitherValues, Randomness}
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.iam.client.types.{AccessControlList, Identity, Permission, ResourceAccessControlList}
import ch.epfl.bluebrain.nexus.kg.config.Schemas.unconstrainedSchemaUri
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources.{Ref, ResId, ResourceF}
import ch.epfl.bluebrain.nexus.kg.storage.AkkaSource
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import io.circe.Json

trait TestHelper extends EitherValues with Randomness {

  private val clock     = Clock.systemUTC()
  val read: Permission  = Permission.unsafe("resources/read")
  val write: Permission = Permission.unsafe("files/write")

  def consume(source: AkkaSource)(implicit mt: Materializer): String = {
    import org.scalatest.concurrent.ScalaFutures._
    source.runFold("")(_ ++ _.utf8String).futureValue
  }

  def produce(string: String, chunkSize: Int = 100): AkkaSource =
    Source(string.grouped(chunkSize).map(ByteString(_)).toList)

  def resourceAcls(acl: AccessControlList): ResourceAccessControlList =
    ResourceAccessControlList(
      url"http://example.com/id",
      1L,
      Set.empty,
      clock.instant(),
      Anonymous,
      clock.instant(),
      Anonymous,
      acl
    )

  def simpleV(
      id: ResId,
      value: Json,
      rev: Long = 1L,
      types: Set[AbsoluteIri] = Set.empty,
      deprecated: Boolean = false,
      schema: Ref = Ref(unconstrainedSchemaUri),
      created: Identity = Anonymous,
      updated: Identity = Anonymous
  )(implicit clock: Clock): ResourceF[Value] =
    ResourceF(
      id,
      rev,
      types,
      deprecated,
      Map.empty,
      None,
      clock.instant(),
      clock.instant(),
      created,
      updated,
      schema,
      Value(value, value.contextValue, value.toGraph(id.value).rightValue)
    )

  def simpleV(res: ResourceF[Json])(implicit clock: Clock) =
    ResourceF(
      res.id,
      res.rev,
      res.types,
      res.deprecated,
      Map.empty,
      None,
      clock.instant(),
      clock.instant(),
      res.createdBy,
      res.updatedBy,
      res.schema,
      Value(res.value, res.value.contextValue, res.value.toGraph(res.id.value).rightValue)
    )

  def genUUID: UUID = UUID.randomUUID()

  def genIri: AbsoluteIri = url"http://example.com/" + genUUID.toString

  private def sourceInChunks(input: String): AkkaSource =
    Source.fromIterator(() => input.grouped(10000).map(ByteString(_)))

  def genSource: AkkaSource = sourceInChunks(genString())

}
