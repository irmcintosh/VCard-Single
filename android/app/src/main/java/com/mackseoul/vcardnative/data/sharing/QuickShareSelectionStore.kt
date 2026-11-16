package com.mackseoul.vcardnative.data.sharing

interface QuickShareSelectionStore {
    fun readConfirmedKeys(): Set<String>

    fun replaceConfirmedKeys(keys: Set<String>)
}