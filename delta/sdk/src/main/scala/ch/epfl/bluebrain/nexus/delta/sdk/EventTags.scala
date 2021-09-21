package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.sdk.model.Event
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.{OrganizationScopedEvent, ProjectScopedEvent, UnScopedEvent}
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
      Projects.projectTag(ev.project),
      Organizations.orgTag(ev.project.organization)
    )

  /**
    * @return
    *   the tags for [[ProjectScopedEvent]] s
    */
  def forProjectScopedEvent[E <: ProjectScopedEvent](moduleTypes: String*)(ev: E): Set[String] =
    forOrganizationScopedEvent(moduleTypes: _*)(ev) ++
      moduleTypes.map(moduleType => Projects.projectTag(moduleType, ev.project)).toSet +
      Projects.projectTag(ev.project)

  /**
    * @return
    *   the tags for [[OrganizationScopedEvent]] s
    */
  def forOrganizationScopedEvent[E <: OrganizationScopedEvent](moduleTypes: String*)(ev: E): Set[String] =
    moduleTypes.toSet ++
      Set(Event.eventTag, Organizations.orgTag(ev.organizationLabel)) ++
      moduleTypes.map(moduleType => Organizations.orgTag(moduleType, ev.organizationLabel)).toSet

  /**
    * @return
    *   the tags for [[UnScopedEvent]] s
    */
  def forUnScopedEvent[E <: UnScopedEvent](moduleType: String)(@unused ev: E): Set[String] =
    Set(Event.eventTag, moduleType)
}
