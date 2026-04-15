package com.github.wechatautohide.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.github.wechatautohide.database.AppDatabase
import com.github.wechatautohide.database.HideContact
import kotlinx.coroutines.launch

class ContactViewModel(application: Application) : AndroidViewModel(application) {
    
    private val dao = AppDatabase.getDatabase(application).hideContactDao()
    
    val allContacts: LiveData<List<HideContact>> = dao.getAllContacts()
    
    fun addContact(contact: HideContact) {
        viewModelScope.launch {
            dao.insert(contact)
        }
    }
    
    fun updateContact(contact: HideContact) {
        viewModelScope.launch {
            dao.update(contact)
        }
    }
    
    fun deleteContact(contact: HideContact) {
        viewModelScope.launch {
            dao.delete(contact)
        }
    }
    
    suspend fun isInHideList(name: String): Boolean {
        return dao.isInHideList(name)
    }
}
