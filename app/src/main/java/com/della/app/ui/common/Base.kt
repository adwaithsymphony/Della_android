package com.della.app.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.della.app.AppContainer
import com.della.app.data.repo.BusinessRuleException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("No container") }
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("No snackbar") }
val LocalNav = compositionLocalOf<NavHostController> { error("No nav") }

/** Base view model: runs actions, turns business-rule failures into user messages. */
open class DellaViewModel : ViewModel() {
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Runs [block] once at a time (double taps are ignored while busy). */
    fun act(success: String? = null, onDone: () -> Unit = {}, block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
                success?.let { messages.emit(it) }
                onDone()
            } catch (e: BusinessRuleException) {
                messages.emit(e.message ?: "Not allowed.")
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                messages.emit("That conflicts with an existing record (duplicate or still in use).")
            } catch (e: Exception) {
                messages.emit("Something went wrong: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _busy.value = false
            }
        }
    }

    protected fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
}

@Composable
inline fun <reified VM : ViewModel> dellaViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

/** Shows a view model's messages in the global snackbar. */
@Composable
fun Messages(vm: DellaViewModel) {
    val snackbar = LocalSnackbar.current
    LaunchedEffect(vm) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
}
