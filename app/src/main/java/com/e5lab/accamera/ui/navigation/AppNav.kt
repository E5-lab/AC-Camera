package com.e5lab.accamera.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.e5lab.accamera.ui.screens.AnalysisScreen
import com.e5lab.accamera.ui.screens.ContourScreen
import com.e5lab.accamera.ui.screens.DashboardScreen
import com.e5lab.accamera.ui.screens.ProjectListScreen
import com.e5lab.accamera.ui.screens.RemediationScreen
import com.e5lab.accamera.ui.screens.SetupScreen
import com.e5lab.accamera.ui.screens.SurveyScreen

object Routes {
    const val PROJECTS = "projects"
    fun project(id: Long) = "project/$id"
    fun setup(id: Long) = "project/$id/setup"
    fun survey(id: Long) = "project/$id/survey"
    fun contour(id: Long) = "project/$id/contour"
    fun analysis(id: Long) = "project/$id/analysis"
    fun remediation(id: Long) = "project/$id/remediation"
}

@Composable
fun AppNav(nav: NavHostController = rememberNavController()) {
    NavHost(navController = nav, startDestination = Routes.PROJECTS) {
        composable(Routes.PROJECTS) { ProjectListScreen(nav) }
        composable("project/{id}") {
            val id = it.arguments?.getString("id")?.toLongOrNull() ?: 0L
            DashboardScreen(nav, id)
        }
        composable("project/{id}/setup") {
            val id = it.arguments?.getString("id")?.toLongOrNull() ?: 0L
            SetupScreen(nav, id)
        }
        composable("project/{id}/survey") {
            val id = it.arguments?.getString("id")?.toLongOrNull() ?: 0L
            SurveyScreen(nav, id)
        }
        composable("project/{id}/contour") {
            val id = it.arguments?.getString("id")?.toLongOrNull() ?: 0L
            ContourScreen(nav, id)
        }
        composable("project/{id}/analysis") {
            val id = it.arguments?.getString("id")?.toLongOrNull() ?: 0L
            AnalysisScreen(nav, id)
        }
        composable("project/{id}/remediation") {
            val id = it.arguments?.getString("id")?.toLongOrNull() ?: 0L
            RemediationScreen(nav, id)
        }
    }
}
