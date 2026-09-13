package com.localscout.app.ui.screens.search

/**
 * Discrete UI branches of the search flow. Used as AnimatedContent's
 * targetState so each branch change (loading → results, results → detail)
 * animates with M3 springs instead of hard-cutting.
 */
enum class SearchBranch {
    IDLE,
    LOADING,
    ERROR,
    PICKER,
    DETAIL,
}

/** Derive the branch from the UI state, matching SearchResultsArea's when. */
fun branchOf(state: SearchUiState): SearchBranch = when {
    state.isSearching -> SearchBranch.LOADING
    state.error != null -> SearchBranch.ERROR
    state.result != null && state.selectedProduct == null -> SearchBranch.PICKER
    state.result != null && state.selectedProduct != null -> SearchBranch.DETAIL
    else -> SearchBranch.IDLE
}