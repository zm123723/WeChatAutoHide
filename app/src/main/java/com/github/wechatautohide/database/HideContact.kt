package com.github.wechatautohide.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hide_contacts")
data class HideContact(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val groupType: String = "其他",
    val autoHide: Boolean = true,
    val muteNotification: Boolean = true,
    val autoRead: Boolean = false,
    val createTime: Long = System.currentTimeMillis(),
    val hideCount: Int = 0,
    val enabled: Boolean = true
)
