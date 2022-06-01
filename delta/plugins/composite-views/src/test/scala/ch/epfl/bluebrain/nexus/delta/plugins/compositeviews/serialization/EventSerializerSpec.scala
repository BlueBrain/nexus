package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.serialization

import akka.actor.{ActorSystem, ExtendedActorSystem}
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViewsFixture
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.EventSerializerBehaviours
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import io.circe.Json
import org.scalatest.CancelAfterFailure
import org.scalatest.flatspec.AnyFlatSpecLike

import scala.collection.immutable.VectorMap

class EventSerializerSpec
    extends TestKit(ActorSystem("EventSerializerSpec"))
    with EventSerializerBehaviours
    with AnyFlatSpecLike
    with TestHelpers
    with CompositeViewsFixture
    with CancelAfterFailure {

  override val serializer = new EventSerializer(system.asInstanceOf[ExtendedActorSystem])

  private val viewSource = jsonContentOf("composite-view-source.json").removeAllKeys("token")
  private val viewId     = iri"http://example.com/composite-view"
  private val tag        = UserTag.unsafe("mytag")

  val mapping: Map[CompositeViewEvent, Json] = VectorMap(
    CompositeViewCreated(viewId, project.ref, uuid, viewValue, viewSource, 1L, epoch, subject) -> jsonContentOf(
      "serialization/view-created.json",
      "uuid" -> uuid
    ),
    CompositeViewUpdated(viewId, project.ref, uuid, viewValue, viewSource, 2L, epoch, subject) -> jsonContentOf(
      "serialization/view-updated.json",
      "uuid" -> uuid
    ),
    CompositeViewTagAdded(viewId, projectRef, uuid, targetRev = 1, tag, 3, epoch, subject)     -> jsonContentOf(
      "serialization/view-tag-added.json",
      "uuid" -> uuid
    ),
    CompositeViewDeprecated(viewId, projectRef, uuid, 4, epoch, subject)                       -> jsonContentOf(
      "serialization/view-deprecated.json",
      "uuid" -> uuid
    )
  )

  "An EventSerializer" should behave like eventToJsonSerializer("compositeviews", mapping)
  "An EventSerializer" should behave like jsonToEventDeserializer("compositeviews", mapping)

}
