package ch.epfl.bluebrain.nexus.iam.index

import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlLists, Resource}
import ch.epfl.bluebrain.nexus.iam.types.Identity
import ch.epfl.bluebrain.nexus.rdf.Iri.Path

trait AclsIndex[F[_]] {

  /**
    * Replaces the current index entry for id ''path'' with the value ''acl''.
    *
    * @param path        the id of the index entry to be replaced
    * @param aclResource the value of the index entry to be replaced
    * @return F(true) if the update was successfully performed, F(false) otherwise
    */
  def replace(path: Path, aclResource: Resource): F[Boolean]

  /**
    * Fetches the [[AccessControlLists]] of the provided ''path'' with some filtering options.
    * The ''path'' can contain the '*' character. This is used to do listings. E.g.: If we have ACLs on path ''/a/b'' and path ''/a/c'',
    * providing a path ''/a/\*'' will target both ''/a/b'' and ''/a/c''.
    *
    * When ''self'' = true, the result only contains ACLs for the provided ''identities''.
    * When ''self'' = false, the result contains ACLs for all the identities that the provided ''identities'' have access to retrieve.
    * The access to a certain identity to retrieve the ACL of another identity depends on the identity having the ''Own'' permission or not.
    *
    * When ''ancestors'' = true, the result contains ACLs for the provided ''path'' and all the parent paths.
    * When ''ancestors'' = false, the result only contains ACLs for the provided ''path''.
    *
    * @param path       the path where the ACLs are going to be looked up
    * @param ancestors  flag to decide whether or not ancestor paths should be included in the response
    * @param self       flag to decide whether or not ancestor other identities than the provided ones should be included in the response
    * @param identities the provided identities
    */
  def get(path: Path, ancestors: Boolean, self: Boolean)(implicit identities: Set[Identity]): F[AccessControlLists]

}
