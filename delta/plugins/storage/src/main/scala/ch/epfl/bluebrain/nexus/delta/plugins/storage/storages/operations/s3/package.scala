package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm

package object s3 {

  val checksumAlgorithm = ChecksumAlgorithm.SHA256

}
