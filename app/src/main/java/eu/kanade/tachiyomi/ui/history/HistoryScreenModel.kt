package eu.kanade.tachiyomi.ui.history

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.util.insertSeparators
import eu.kanade.presentation.history.HistoryUiModel
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.model.HistoryWithRelations
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class HistoryScreenModel(
    private val getHistory: GetHistory = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val removeHistory: RemoveHistory = Injekt.get(),
) : StateScreenModel<HistoryScreenModel.State>(State()) {

    private val _events: Channel<Event> = Channel(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        screenModelScope.launch {
            state.map { it.searchQuery }
                .distinctUntilChanged()
                .flatMapLatest { query ->
                    getHistory.subscribe(query ?: "")
                        .distinctUntilChanged()
                        .catch { error ->
                            logcat(LogPriority.ERROR, error)
                            _events.send(Event.InternalError)
                        }
                        .map { it.toHistoryUiModels().toImmutableList() }
                        .flowOn(Dispatchers.IO)
                }
                .collect { newList -> mutableState.update { it.copy(list = newList) } }
        }
    }

    private fun List<HistoryWithRelations>.toHistoryUiModels(): List<HistoryUiModel> {
        return map { history -> HistoryUiModel.Item(history) }
            .insertSeparators { before, after ->
                val beforeDate = before?.item?.readAt?.time?.toLocalDate()
                val afterDate = after?.item?.readAt?.time?.toLocalDate()
                when {
                    beforeDate != afterDate && afterDate != null -> HistoryUiModel.Header(afterDate)
                    // Return null to avoid adding a separator between two items.
                    else -> null
                }
            }
    }

    suspend fun getNextChapter(): Chapter? {
        return withIOContext { getNextChapters.await(onlyUnread = false).firstOrNull() }
    }

    fun resumeManga(chapter: Chapter?) {
        screenModelScope.launchIO {
            if (chapter == null) return@launchIO
            val nextChapters = getNextChapters.await(chapter.mangaId, chapter.id, onlyUnread = false)
            if (nextChapters.firstOrNull()?.read == true) {
                sendNextChapterEvent(nextChapters.toMutableList().apply { add(0, chapter) })
            } else {
                sendNextChapterEvent(nextChapters)
            }
        }
    }

    private suspend fun sendNextChapterEvent(chapters: List<Chapter>) {
        val chapter = chapters.firstOrNull()
        _events.send(Event.OpenChapter(chapter))
    }

    fun toggleExpandHistory(historyItem: HistoryWithRelations) {
        val mangaId = historyItem.mangaId
        screenModelScope.launch {
            val currentState = mutableState.value
            val isExpanded = currentState.expandedStates[mangaId] ?: false
            mutableState.update {
                it.copy(expandedStates = it.expandedStates.apply { this[mangaId] = !isExpanded })
            }

            if (!isExpanded) { // If expanding, load previous history if not already loaded
                if ((currentState.list?.find {
                    it is HistoryUiModel.Item && it.item.mangaId == mangaId
                } as? HistoryUiModel.Item)?.previousHistory == null) {
                    loadPreviousHistory(historyItem)
                }
            }
        }
    }

    private fun loadPreviousHistory(historyItem: HistoryWithRelations) {
        screenModelScope.launch {
            val previousHistoryList = getHistory.await(historyItem.mangaId)
                .filter { (it.readAt?.time ?: 0) > 0 }
                .sortedByDescending { it.readAt }
                .map { HistoryWithRelations.from(it, historyItem) }
//                .drop(1) //remove current history from previous history list
                .toImmutableList()

            mutableState.update { currentState ->
                val newList = currentState.list?.map { uiModel ->
                    if (uiModel is HistoryUiModel.Item && uiModel.item.mangaId == historyItem.mangaId)
                        uiModel.copy(previousHistory = previousHistoryList)
                    else uiModel
                }?.toImmutableList()

                currentState.copy(list = newList ?: currentState.list) // Update list only if not null
            }
        }
    }

    fun removeFromHistory(history: HistoryWithRelations) {
        screenModelScope.launchIO {
            removeHistory.await(history)
        }
    }

    fun removeAllFromHistory(mangaId: Long) {
        screenModelScope.launchIO {
            removeHistory.await(mangaId)
        }
    }

    fun removeAllHistory() {
        screenModelScope.launchIO {
            val result = removeHistory.awaitAll()
            if (!result) return@launchIO
            _events.send(Event.HistoryCleared)
        }
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    @Immutable
    data class State(
        val searchQuery: String? = null,
        val list: ImmutableList<HistoryUiModel>? = null,
        val dialog: Dialog? = null,
        val expandedStates: MutableMap<Long, Boolean> = mutableStateMapOf(),
    )

    sealed interface Dialog {
        data object DeleteAll : Dialog
        data class Delete(val history: HistoryWithRelations) : Dialog
    }

    sealed interface Event {
        data class OpenChapter(val chapter: Chapter?) : Event
        data object InternalError : Event
        data object HistoryCleared : Event
    }
}
