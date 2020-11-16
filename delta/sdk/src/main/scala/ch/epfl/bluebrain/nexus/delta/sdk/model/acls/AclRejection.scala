package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of ACLS rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class AclRejection(val reason: String) extends Product with Serializable

object AclRejection {

  /**
    * Signals an attempt to append/subtract ACLs that won't change the current state.
    *
    * @param address the ACL address
    */
  final case class NothingToBeUpdated(address: AclAddress)
      extends AclRejection(s"The ACL on address '$address' will not change after applying the provided update.")

  /**
    * Signals an attempt to modify ACLs that do not exists.
    *
    * @param address the ACL address
    */
  final case class AclNotFound(address: AclAddress) extends AclRejection(s"The ACL address '$address' does not exists.")

  /**
    * Signals an attempt to delete ACLs that are already empty.
    *
    * @param address the ACL address
    */
  final case class AclIsEmpty(address: AclAddress) extends AclRejection(s"The ACL on address '$address' is empty.")

  /**
    * Signals an attempt to interact with an ACL collection with an incorrect revision.
    *
    * @param address  the ACL address
    * @param provided the provided revision
    * @param expected the expected revision
    */
  final case class IncorrectRev(address: AclAddress, provided: Long, expected: Long)
      extends AclRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the ACL address '$address' may have been updated since last seen."
      )

  /**
    * Signals an attempt to retrieve the ACL at a specific revision when the provided revision does not exist.
    *
    * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends AclRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Signals an attempt to create/replace/append/subtract ACL collection which contains void permissions.
    *
    * @param address the ACL address
    */
  final case class AclCannotContainEmptyPermissionCollection(address: AclAddress)
      extends AclRejection(s"The ACL address '$address' cannot contain an empty permission collection.")

  /**
    * Signals that an acl operation could not be performed because of unknown referenced permissions.
    *
    * @param permissions the unknown permissions
    */
  final case class UnknownPermissions(permissions: Set[Permission])
      extends AclRejection(
        s"Some of the permissions specified are not known: '${permissions.mkString("\"", ", ", "\"")}'"
      )

  /**
    * Rejection returned when the returned state is the initial state after a Acls.evaluation plus a Acls.next
    * Note: This should never happen since the evaluation method already guarantees that the next function returns a current
    */
  final case class UnexpectedInitialState(address: AclAddress)
      extends AclRejection(s"Unexpected initial state for acl address '$address'.")

  implicit private val aclRejectionEncoder: Encoder.AsObject[AclRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
    }

  implicit final val aclRejectionJsonLdEncoder: JsonLdEncoder[AclRejection] =
    JsonLdEncoder.fromCirce(id = BNode.random, iriContext = contexts.error)
}
