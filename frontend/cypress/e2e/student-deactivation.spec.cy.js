// UC-STU-deactivate-student T1/T7/T9/T10. All API replies are fixtures:
// these tests prove UI behavior, not server authorization or persistence.
const envelope = (data) => ({ flag: true, code: 200, message: 'Success', data })
const initialStudent = {
  id: 3,
  username: 'j.smith@abc.edu',
  firstName: 'John',
  lastName: 'Smith',
  email: 'j.smith@abc.edu',
  enabled: true,
  roles: 'student',
  sectionId: 1,
  teamId: 1,
  teamName: 'Team1'
}

function session(win) {
  const token = `fixture.${win.btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }))}.fixture`
  win.localStorage.setItem('token', JSON.stringify({ token }))
  win.localStorage.setItem(
    'userInfo',
    JSON.stringify({ userInfo: { id: 1, firstName: 'Instructor', roles: 'admin instructor' } })
  )
  win.localStorage.setItem('settings', JSON.stringify({ defaultSectionId: 1, defaultCourseId: 1 }))
}

describe('Student deactivation warning (fixture APIs)', () => {
  let student
  beforeEach(() => {
    student = { ...initialStudent }
    cy.intercept('POST', '**/students/search*', (req) =>
      req.reply(
        envelope({
          content: [student],
          number: 0,
          size: 50,
          totalElements: 1
        })
      )
    ).as('search')
    cy.intercept('GET', '**/students/3', (req) => req.reply(envelope(student))).as('student')
    cy.intercept('POST', '**/students/3/deactivate', (req) => {
      student = { ...student, enabled: false }
      req.reply({ delay: 500, body: envelope(student) })
    }).as('deactivate')
  })

  function edit() {
    cy.visit('/students', { onBeforeLoad: session })
    cy.wait('@search')
    cy.get('[data-cy=student-edit]').click()
    cy.wait('@student')
  }
  function warning() {
    edit()
    cy.get('[data-cy=deactivate-open]').click()
    cy.get('[data-cy=deactivate-dialog]').should('be.visible')
  }

  it('states all consequences before any request (T1 step 4)', () => {
    warning()
    cy.get('[data-cy=deactivate-consequences]')
      .should('contain', 'existing-session access')
      .and('contain', 'no submission reminders')
      .and('contain', 'cannot create new evaluations')
      .and('contain', 'team membership')
      .and('contain', 'previously submitted work')
      .and('contain', 'weeks with received evaluations')
      .and('contain', 'scores and comments')
    cy.get('@deactivate.all').should('have.length', 0)
    cy.screenshot('student-deactivation-warning')
  })

  for (const dismissal of ['Cancel', 'close', 'Escape']) {
    it(`sends no request on ${dismissal} (T9, extension 4a)`, () => {
      warning()
      if (dismissal === 'Cancel') cy.get('[data-cy=deactivate-cancel]').click()
      if (dismissal === 'close') cy.get('[data-cy=deactivate-dialog] .el-dialog__headerbtn').click()
      if (dismissal === 'Escape') cy.get('body').type('{esc}')
      cy.get('[data-cy=deactivate-dialog]').should('not.be.visible')
      cy.get('@deactivate.all').should('have.length', 0)
      cy.get('[data-cy=student-active-status]').should('contain', 'Enabled')
    })
  }

  it('confirms once while pending and refreshes inactive status (T1/T9)', () => {
    warning()
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.get('[data-cy=deactivate-confirm]').should('be.disabled').click({ force: true })
    cy.wait('@deactivate').its('request.body').should('be.empty')
    cy.get('@deactivate.all').should('have.length', 1)
    cy.get('[data-cy=deactivate-dialog]').should('not.exist')
    cy.get('.el-message--success').should('contain', 'Student deactivated successfully')
    cy.contains('.el-table__row', 'John').should('contain', 'Disabled')
  })

  it('shows refusal, reloads without success, then completes a confirmed retry (T9)', () => {
    let attempts = 0
    cy.intercept('POST', '**/students/3/deactivate', (req) => {
      attempts += 1
      if (attempts === 1)
        req.reply({ statusCode: 500, body: { flag: false, code: 500, message: 'Test refusal' } })
      else {
        student = { ...student, enabled: false }
        req.reply(envelope(student))
      }
    }).as('retry')
    warning()
    cy.get('@student.all').then((before) => {
      cy.get('[data-cy=deactivate-confirm]').click()
      cy.wait('@retry')
      cy.get('@student.all').should('have.length', before.length + 1)
    })
    cy.get('.el-message--success').should('not.exist')
    cy.get('.el-message--error').should('contain', 'Test refusal')
    cy.get('[data-cy=deactivate-open]').should('be.enabled').click()
    cy.get('[data-cy=deactivate-dialog]').should('be.visible')
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.wait('@retry')
    cy.get('@retry.all').should('have.length', 2)
    cy.contains('.el-table__row', 'John').should('contain', 'Disabled')
    cy.get('.el-message--success').should('contain', 'Student deactivated successfully')
  })

  it('reloads status after a lost response without claiming success (T9)', () => {
    cy.intercept('POST', '**/students/3/deactivate', (req) => {
      student = { ...student, enabled: false }
      req.destroy()
    }).as('lost')
    warning()
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.wait('@lost')
    cy.get('[data-cy=student-reactivate]').should('exist')
    cy.get('[data-cy=deactivate-open]').should('not.exist')
    cy.get('.el-message--success').should('not.exist')
  })

  it('hides deactivation for inactive accounts and preserves explicit reactivation baseline (T7/T10)', () => {
    student.enabled = false
    cy.intercept('PUT', '**/students/3*', (req) => req.reply(envelope(req.body))).as('save')
    edit()
    cy.get('[data-cy=deactivate-open]').should('not.exist')
    cy.get('[data-cy=student-reactivate]').click()
    cy.get('[data-cy=student-save]').click()
    cy.wait('@save').then(({ request }) => {
      expect(request.query.expectedEnabled).to.eq('false')
      expect(request.body.enabled).to.eq(true)
    })
  })

  it('sends observed status and reloads 409 without reporting a successful save (T10)', () => {
    cy.intercept('PUT', '**/students/3*', (req) => {
      expect(req.query.expectedEnabled).to.eq('true')
      student = { ...student, enabled: false }
      req.reply({ statusCode: 409, body: { flag: false, code: 409, message: 'Stale status' } })
    }).as('stale')
    edit()
    cy.get('[data-cy=student-save]').click()
    cy.wait('@stale')
    cy.get('[data-cy=student-reactivate]').should('exist')
    cy.get('[data-cy=deactivate-open]').should('not.exist')
    cy.get('.el-message--success').should('not.exist')
  })
})
