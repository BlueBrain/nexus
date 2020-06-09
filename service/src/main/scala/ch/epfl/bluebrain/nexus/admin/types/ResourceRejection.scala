package ch.epfl.bluebrain.nexus.admin.types

import ch.epfl.bluebrain.nexus.service.routes.ServiceRejection

/**
  * Parent type to all resource rejections for admin.
  */
trait ResourceRejection extends ServiceRejection
