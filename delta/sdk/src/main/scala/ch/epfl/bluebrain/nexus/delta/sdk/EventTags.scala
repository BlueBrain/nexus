package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.sdk.model.Event
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.{ProjectScopedEvent, UnScopedEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent

import scala.annotation.unused

/**
  * Provides the tags for certain events
  */
object EventTags {

  /**
    * @return
    *   the tags for [[ResourceEvent]] s
    */
  def forResourceEvents(moduleType: String)(ev: ResourceEvent): Set[String] =
    Set(
      Event.eventTag,
      moduleType,
      Projects.projectTag(ev.project)
    )

  /**
    * @return
    *   the tags for [[ProjectScopedEvent]] s
    */
  def forProjectScopedEvent[E <: ProjectScopedEvent](moduleTypes: String*)(ev: E): Set[String] =
    moduleTypes.map(moduleType => Projects.projectTag(moduleType, ev.project)).toSet +
      Projects.projectTag(ev.project)

  /**
    * @return
    *   the tags for [[UnScopedEvent]] s
    */
  def forUnScopedEvent[E <: UnScopedEvent](moduleType: String)(@unused ev: E): Set[String] =
    Set(Event.eventTag, moduleType)
}
