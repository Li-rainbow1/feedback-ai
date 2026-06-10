import { createRouter, createWebHashHistory } from 'vue-router'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'Login', component: () => import('../views/Login.vue') },
    { path: '/dashboard', name: 'Dashboard', component: () => import('../views/Dashboard.vue') },
    { path: '/feedbacks', name: 'Feedbacks', component: () => import('../views/FeedbackList.vue') },
    { path: '/feedbacks/:id', name: 'FeedbackDetail', component: () => import('../views/FeedbackDetail.vue') },
    { path: '/issues', redirect: '/issues/bugs' },
    { path: '/issues/bugs', name: 'BugIssues', component: () => import('../views/IssueList.vue'), props: { fixedCategory: 'BUG', pageTitle: 'Bug 问题' } },
    { path: '/issues/suggestions', name: 'SuggestionIssues', component: () => import('../views/IssueList.vue'), props: { fixedCategory: 'SUGGESTION', pageTitle: '建议池' } },
    { path: '/issues/:id', name: 'IssueDetail', component: () => import('../views/IssueDetail.vue') },
    { path: '/praises', name: 'Praises', component: () => import('../views/PraiseList.vue') },
    { path: '/reports', name: 'Reports', component: () => import('../views/WeeklyReportList.vue') },
    { path: '/channels', name: 'Channels', component: () => import('../views/ChannelConfig.vue') },
    { path: '/public-reviews', name: 'PublicReviews', component: () => import('../views/PublicReviewSources.vue') },
    { path: '/products', name: 'Products', component: () => import('../views/ProductManagement.vue') },
    { path: '/users', name: 'Users', component: () => import('../views/UserManagement.vue') },
    { path: '/', redirect: '/dashboard' },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
  ]
})

router.beforeEach((to, _from, next) => {
  const user = localStorage.getItem('user')
  const token = localStorage.getItem('token')
  if ((!user || !token) && to.path !== '/login') next('/login')
  else next()
})

export default router
