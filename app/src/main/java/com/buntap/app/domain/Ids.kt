package com.buntap.app.domain

import java.util.UUID

/** Generates unique identifiers for points, targets, routines and actions. */
object Ids {
    fun newId(): String = UUID.randomUUID().toString()
}
