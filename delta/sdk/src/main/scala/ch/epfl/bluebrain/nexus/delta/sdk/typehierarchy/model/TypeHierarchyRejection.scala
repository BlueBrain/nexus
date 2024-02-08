package ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model

import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection

sealed abstract class TypeHierarchyRejection(val reason: String) extends Rejection

object TypeHierarchyRejection {

  /**
    * Enumeration of possible reasons why type hierarchy is not found
    */
  sealed abstract class NotFound(reason: String) extends TypeHierarchyRejection(reason)

  /**
    * Signals that the type hierarchy does not exist.
    */
  final case object TypeHierarchyDoesNotExist extends NotFound("The type hierarchy does not exist.")

  /**
    * Signals an attempt to retrieve a type hierarchy at a specific revision when the provided revision does not exist.
    *
    * @param provided
    *   the provided revision
    * @param current
    *   the last known revision
    */
  final case class RevisionNotFound(provided: Int, current: Int)
      extends NotFound(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Signals that the provided revision does not match the latest revision
    *
    * @param provided
    *   provided revision
    * @param expected
    *   latest know revision
    */
  final case class IncorrectRev(provided: Int, expected: Int)
      extends TypeHierarchyRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the type hierarchy may have been updated since last seen."
      )

  /**
    * Signals the type hierarchy already exists.
    */
  final case object TypeHierarchyAlreadyExists extends TypeHierarchyRejection(s"Type hierarchy already exists.")

}
