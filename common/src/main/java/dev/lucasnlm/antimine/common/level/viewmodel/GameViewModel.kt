package dev.lucasnlm.antimine.common.level.viewmodel

import android.app.Application
import android.os.Handler
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dev.lucasnlm.antimine.common.level.repository.MinefieldRepository
import dev.lucasnlm.antimine.common.level.LevelFacade
import dev.lucasnlm.antimine.common.level.models.Area
import dev.lucasnlm.antimine.common.level.models.Difficulty
import dev.lucasnlm.antimine.common.level.models.Event
import dev.lucasnlm.antimine.common.level.models.Minefield
import dev.lucasnlm.antimine.common.level.database.models.Save
import dev.lucasnlm.antimine.common.level.repository.IDimensionRepository
import dev.lucasnlm.antimine.common.level.repository.ISavesRepository
import dev.lucasnlm.antimine.common.level.utils.Clock
import dev.lucasnlm.antimine.common.level.utils.IHapticFeedbackInteractor
import dev.lucasnlm.antimine.core.analytics.AnalyticsManager
import dev.lucasnlm.antimine.core.analytics.models.Analytics
import dev.lucasnlm.antimine.core.preferences.IPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GameViewModel(
    val application: Application,
    val eventObserver: MutableLiveData<Event>,
    private val savesRepository: ISavesRepository,
    private val dimensionRepository: IDimensionRepository,
    private val preferencesRepository: IPreferencesRepository,
    private val hapticFeedbackInteractor: IHapticFeedbackInteractor,
    private val minefieldRepository: MinefieldRepository,
    private val analyticsManager: AnalyticsManager,
    private val clock: Clock
) : ViewModel() {
    private lateinit var levelFacade: LevelFacade
    private var currentDifficulty: Difficulty = Difficulty.Standard
    private var initialized = false

    val field = MutableLiveData<Sequence<Area>>()
    val fieldRefresh = MutableLiveData<Int>()
    val elapsedTimeSeconds = MutableLiveData<Long>()
    val mineCount = MutableLiveData<Int>()
    val difficulty = MutableLiveData<Difficulty>()
    val levelSetup = MutableLiveData<Minefield>()

    fun startNewGame(newDifficulty: Difficulty = currentDifficulty): Minefield {
        clock.reset()
        elapsedTimeSeconds.postValue(0L)
        currentDifficulty = newDifficulty

        val minefield = minefieldRepository.fromDifficulty(
            newDifficulty, dimensionRepository, preferencesRepository
        )

        levelFacade = LevelFacade(minefield)

        mineCount.postValue(minefield.mines)
        difficulty.postValue(newDifficulty)
        levelSetup.postValue(minefield)
        refreshAll()

        eventObserver.postValue(Event.StartNewGame)

        analyticsManager.sentEvent(
            Analytics.NewGame(
                minefield, newDifficulty,
                levelFacade.seed,
                useAccessibilityMode()
            )
        )

        return minefield
    }

    private fun resumeGameFromSave(save: Save): Minefield {
        clock.reset(save.duration)
        elapsedTimeSeconds.postValue(save.duration)

        val setup = save.minefield
        levelFacade = LevelFacade(save)

        mineCount.postValue(setup.mines)
        difficulty.postValue(save.difficulty)
        levelSetup.postValue(setup)
        refreshAll()

        when {
            levelFacade.hasAnyMineExploded() -> eventObserver.postValue(Event.ResumeGameOver)
            levelFacade.checkVictory() -> eventObserver.postValue(Event.ResumeVictory)
            else -> eventObserver.postValue(Event.ResumeGame)
        }

        analyticsManager.sentEvent(Analytics.ResumePreviousGame())
        return setup
    }

    suspend fun onCreate(newGame: Difficulty? = null): Minefield = withContext(Dispatchers.IO) {
        val lastGame = if (newGame == null) savesRepository.fetchCurrentSave() else null

        if (lastGame != null) {
            currentDifficulty = lastGame.difficulty
        } else if (newGame != null) {
            currentDifficulty = newGame
        }

        if (lastGame == null) {
            startNewGame(currentDifficulty)
        } else {
            resumeGameFromSave(lastGame)
        }.also {
            initialized = true
        }
    }

    fun pauseGame() {
        if (initialized) {
            if (levelFacade.hasMines) {
                eventObserver.postValue(Event.Pause)
            }
            clock.stop()
        }
    }

    suspend fun saveGame() {
        if (initialized && levelFacade.hasMines) {
            val id = savesRepository.saveGame(
                levelFacade.getSaveState(elapsedTimeSeconds.value ?: 0L, currentDifficulty)
            )
            levelFacade.setCurrentSaveId(id?.toInt() ?: 0)
        }
    }

    fun resumeGame() {
        if (initialized && levelFacade.hasMines) {
            eventObserver.postValue(Event.Resume)
        }
    }

    fun onLongClick(index: Int) {
        levelFacade.turnOffAllHighlighted()
        refreshAll()

        if (levelFacade.hasCoverOn(index)) {
            levelFacade.switchMarkAt(index).run {
                refreshIndex(id)
                hapticFeedbackInteractor.toggleFlagFeedback()
            }

            analyticsManager.sentEvent(Analytics.LongPressArea(index))
        } else {
            levelFacade.openNeighbors(index).forEach { refreshIndex(it.id) }

            analyticsManager.sentEvent(Analytics.LongPressMultipleArea(index))
        }

        updateGameState()
    }

    fun onClickArea(index: Int) {
        if (levelFacade.turnOffAllHighlighted()) {
            refreshAll()
        }

        if (levelFacade.hasMarkOn(index)) {
            levelFacade.removeMark(index).run {
                refreshIndex(id)
            }
            hapticFeedbackInteractor.toggleFlagFeedback()
        } else {
            if (!levelFacade.hasMines) {
                levelFacade.plantMinesExcept(index, true)
            }

            levelFacade.clickArea(index).run {
                refreshIndex(index, this)
            }
        }

        if (preferencesRepository.useFlagAssistant() && !levelFacade.hasAnyMineExploded()) {
            levelFacade.runFlagAssistant().forEach {
                Handler().post {
                    refreshIndex(it.id)
                }
            }
        }

        updateGameState()
        analyticsManager.sentEvent(Analytics.PressArea(index))
    }

    private fun refreshMineCount() = mineCount.postValue(levelFacade.remainingMines())

    private fun updateGameState() {
        when {
            levelFacade.hasAnyMineExploded() -> {
                hapticFeedbackInteractor.explosionFeedback()
                eventObserver.postValue(Event.GameOver)
            }
            else -> {
                eventObserver.postValue(Event.Running)
            }
        }

        if (levelFacade.hasMines) {
            refreshMineCount()
        }

        if (levelFacade.checkVictory()) {
            eventObserver.postValue(Event.Victory)
        }
    }

    fun runClock() {
        clock.run {
            if (isStopped) start {
                elapsedTimeSeconds.postValue(it)
            }
        }
    }

    fun stopClock() {
        clock.stop()
    }

    fun revealAllEmptyAreas() = levelFacade.revealAllEmptyAreas()

    fun explosionDelay() = 750L

    suspend fun gameOver() {
        levelFacade.run {
            analyticsManager.sentEvent(Analytics.GameOver(clock.time(), getScore()))
            val delayMillis = explosionDelay() / levelFacade.mines.count().coerceAtLeast(10)

            findExplodedMine()?.let { exploded ->
                takeExplosionRadius(exploded).forEach {
                    it.isCovered = false
                    refreshIndex(it.id)
                    delay(delayMillis)
                }
            }

            showWrongFlags()
            refreshAll()
            updateGameState()
        }

        GlobalScope.launch {
            saveGame()
        }
    }

    fun victory() {
        levelFacade.run {
            analyticsManager.sentEvent(
                Analytics.Victory(
                    clock.time(),
                    getScore(),
                    currentDifficulty
                )
            )
            flagAllMines()
            showWrongFlags()
        }

        GlobalScope.launch {
            saveGame()
        }
    }

    fun useAccessibilityMode() = preferencesRepository.useLargeAreas()

    private fun refreshIndex(targetIndex: Int, changes: Int = 1) {
        if (changes > 1) {
            field.postValue(levelFacade.field)
        } else {
            fieldRefresh.postValue(targetIndex)
        }
    }

    private fun refreshAll() {
        field.postValue(levelFacade.field)
    }
}
