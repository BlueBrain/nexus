package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.sdk.model.Event
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.{OrganizationScopedEvent, ProjectScopedEvent, UnScopedEvent}

import scala.annotation.unused

/**
  * Provides the tags for certain events
  */
object EventTags {

  /**
    * @return the tags for [[ProjectScopedEvent]]s
    */
  def forProjectScopedEvent[E <: ProjectScopedEvent](moduleType: String)(ev: E): Set[String] =
    Set(
      Event.eventTag,
      moduleType,
      Projects.projectTag(moduleType, ev.project),
      Projects.projectTag(ev.project),
      Organizations.orgTag(moduleType, ev.project.organization),
      Organizations.orgTag(ev.project.organization)
    )

  /**
    * @return the tags for [[OrganizationScopedEvent]]s
    */
  def forOrganizationScopedEvent[E <: OrganizationScopedEvent](moduleType: String)(ev: E): Set[String] =
    Set(
      Event.eventTag,
      moduleType,
      Organizations.orgTag(moduleType, ev.label),
      Organizations.orgTag(ev.label)
    )

  /**
    * @return the tags for [[UnScopedEvent]]s
    */
  def forUnScopedEvent[E <: UnScopedEvent](moduleType: String)(@unused ev: E): Set[String] =
    Set(Event.eventTag, moduleType)
}
