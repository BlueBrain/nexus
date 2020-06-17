package ch.epfl.bluebrain.nexus.kg.persistence

import java.time.{Clock, Instant, ZoneId}

import akka.persistence.journal.Tagged
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary._
import ch.epfl.bluebrain.nexus.kg.persistence.TaggingAdapterSpec.Other
import ch.epfl.bluebrain.nexus.kg.resources.Event._
import ch.epfl.bluebrain.nexus.kg.resources.{Id, OrganizationRef, Ref}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import io.circe.Json
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class TaggingAdapterSpec extends AnyWordSpecLike with Matchers with Inspectors with TestHelper {

  "A TaggingAdapter" should {
    val clock = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.systemDefault())

    def genJson(): Json = Json.obj("key" -> Json.fromString(genString()))

    val adapter = new TaggingAdapter()
    val orgRef  = OrganizationRef(genUUID)
    val id      = Id(ProjectRef(genUUID), nxv.projects)

    val mapping = Map(
      Set(
        s"type=${nxv.Schema.value.show}",
        s"type=${nxv.Resource.value.show}",
        s"project=${id.parent.id}",
        s"org=${orgRef.show}",
        "event"
      )                                                                                                   ->
        Created(id, orgRef, Ref(shaclSchemaUri), Set(nxv.Schema, nxv.Resource), genJson(), clock.instant(), Anonymous),
      Set(
        s"type=${nxv.Resolver.value.show}",
        s"type=${nxv.Resource.value.show}",
        s"project=${id.parent.id}",
        s"org=${orgRef.show}",
        "event"
      )                                                                                                   ->
        Updated(id, orgRef, 1L, Set(nxv.Resource, nxv.Resolver), genJson(), clock.instant(), Anonymous),
      Set(s"type=${nxv.Resource.value.show}", s"project=${id.parent.id}", s"org=${orgRef.show}", "event") ->
        Deprecated(id, orgRef, 1L, Set(nxv.Resource), clock.instant(), Anonymous),
      Set(s"project=${id.parent.id}", s"org=${orgRef.show}", "event")                                     ->
        TagAdded(id, orgRef, 2L, 1L, "tag", clock.instant(), Anonymous)
    )

    "set the appropriate tags" in {
      forAll(mapping.toList) {
        case (tags, ev) => adapter.toJournal(ev) shouldEqual Tagged(ev, tags)
      }
    }

    "return an empty manifest" in {
      adapter.manifest(Other(genString())) shouldEqual ""
    }
  }
}

object TaggingAdapterSpec {
  final private[persistence] case class Other(value: String)

}
