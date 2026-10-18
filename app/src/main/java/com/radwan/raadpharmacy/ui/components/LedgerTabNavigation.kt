package com.radwan.raadpharmacy.ui.components

import androidx.navigation.NavHostController

/** Save bottom destinations independently; never clear a financial form through this helper. */
internal fun NavHostController.navigateLedgerTab(route: String) {
    if(currentDestination?.route==route) return
    val hasHome=runCatching { getBackStackEntry("home") }.isSuccess
    navigate(route) {
        if(hasHome) popUpTo("home") {saveState=true} else popUpTo(graph.id) {saveState=true}
        launchSingleTop=true
        restoreState=true
    }
}
