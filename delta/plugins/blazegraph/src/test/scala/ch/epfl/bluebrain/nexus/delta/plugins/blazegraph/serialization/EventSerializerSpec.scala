package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.serialization

import akka.actor.{ActorSystem, ExtendedActorSystem}
import akka.testkit.TestKit
import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.{AggregateBlazegraphViewValue, IndexingBlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewEvent, ViewRef}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.EventSerializerBehaviours
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import io.circe.Json
import org.scalatest.CancelAfterFailure
import org.scalatest.flatspec.AnyFlatSpecLike

import java.time.Instant
import java.util.UUID
import scala.collection.immutable.VectorMap

class EventSerializerSpec
    extends TestKit(ActorSystem("EventSerializerSpec"))
    with EventSerializerBehaviours
    with AnyFlatSpecLike
    with TestHelpers
    with CancelAfterFailure {

  override val serializer = new EventSerializer(system.asInstanceOf[ExtendedActorSystem])

  private val uuid             = UUID.fromString("f8468909-a797-4b10-8b5f-000cba337bfa")
  private val instant: Instant = Instant.EPOCH
  private val subject: Subject = User("username", Label.unsafe("myrealm"))
  private val tag              = TagLabel.unsafe("mytag")
  private val projectRef       = ProjectRef.unsafe("myorg", "myproj")
  private val indexingId       = nxv + "indexing-view"
  private val aggregateId      = nxv + "aggregate-view"
  private val indexingValue    = IndexingBlazegraphViewValue(
    Set(nxv + "some-schema"),
    Set(nxv + "SomeType"),
    Some(TagLabel.unsafe("some.tag")),
    includeMetadata = false,
    includeDeprecated = false,
    Permission.unsafe("my/permission")
  )
  private val viewRef          = ViewRef(projectRef, indexingId)
  private val aggregateValue   = AggregateBlazegraphViewValue(NonEmptySet.one(viewRef))

  private val indexingSource  = indexingValue.toJson(indexingId)
  private val aggregateSource = aggregateValue.toJson(aggregateId)

  // format: off
  private val blazegraphViewsMapping: Map[BlazegraphViewEvent, Json] = VectorMap(
    BlazegraphViewCreated(indexingId, projectRef, uuid, indexingValue, indexingSource, 1, instant, subject)    -> jsonContentOf("/serialization/indexing-view-created.json"),
    BlazegraphViewCreated(aggregateId, projectRef, uuid, aggregateValue, aggregateSource, 1, instant, subject) -> jsonContentOf("/serialization/aggregate-view-created.json"),
    BlazegraphViewUpdated(indexingId, projectRef, uuid, indexingValue, indexingSource, 2, instant, subject)    -> jsonContentOf("/serialization/indexing-view-updated.json"),
    BlazegraphViewUpdated(aggregateId, projectRef, uuid, aggregateValue, aggregateSource, 2, instant, subject) -> jsonContentOf("/serialization/aggregate-view-updated.json"),
    BlazegraphViewTagAdded(indexingId, projectRef, uuid, targetRev = 1, tag, 3, instant, subject)              -> jsonContentOf("/serialization/view-tag-added.json"),
    BlazegraphViewDeprecated(indexingId, projectRef, uuid, 4, instant, subject)                                -> jsonContentOf("/serialization/view-deprecated.json")
  )
  // format: on

  "An EventSerializer" should behave like eventToJsonSerializer("blazegraph", blazegraphViewsMapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("blazegraph", blazegraphViewsMapping)

}
