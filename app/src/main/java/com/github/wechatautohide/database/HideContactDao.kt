package com.github.wechatautohide.database

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface HideContactDao {
    
    @Query("SELECT * FROM hide_contacts WHERE enabled = 1 ORDER BY createTime DESC")
    fun getAllContacts(): LiveData<List<HideContact>>
    
    @Query("SELECT * FROM hide_contacts WHERE enabled = 1 ORDER BY createTime DESC")
    suspend fun getAllContactsSync(): List<HideContact>
    
    @Query("SELECT * FROM hide_contacts WHERE name = :name AND enabled = 1 LIMIT 1")
    suspend fun getContactByName(name: String): HideContact?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: HideContact): Long
    
    @Update
    suspend fun update(contact: HideContact)
    
    @Delete
    suspend fun delete(contact: HideContact)
    
    @Query("UPDATE hide_contacts SET hideCount = hideCount + 1 WHERE id = :id")
    suspend fun incrementHideCount(id: Long)
    
    @Query("SELECT EXISTS(SELECT 1 FROM hide_contacts WHERE name = :name AND enabled = 1)")
    suspend fun isInHideList(name: String): Boolean
}
