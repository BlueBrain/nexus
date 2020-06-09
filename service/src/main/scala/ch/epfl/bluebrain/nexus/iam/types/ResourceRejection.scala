package ch.epfl.bluebrain.nexus.iam.types

import ch.epfl.bluebrain.nexus.service.routes.ServiceRejection

/**
  * Parent type to all resource rejections for iam.
  */
trait ResourceRejection extends ServiceRejection
