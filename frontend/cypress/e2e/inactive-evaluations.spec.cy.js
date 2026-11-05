// T3/T5, BR-evaluation-inactive-student. Fixture-only UI tests; real API tests
// independently exercise inactive creation refusal and retained-edit windows.
const envelope = (data) => ({ flag: true, code: 200, message: 'Success', data })
const active = {
  id: 3,
  firstName: 'John',
  lastName: 'Smith',
  enabled: true,
  teamId: 1,
  sectionId: 1
}
const inactive = {
  id: 4,
  firstName: 'Eric',
  lastName: 'Wong',
  enabled: false,
  teamId: 1,
  sectionId: 1
}
const retained = {
  evaluationId: 12,
  week: '2023-W32',
  evaluatorId: 3,
  evaluateeId: 4,
  evaluateeName: 'Eric Wong',
  ratings: [{ criterionId: 1, criterion: 'Quality', actualScore: 7 }],
  publicComment: 'Retained public',
  privateComment: 'Retained private'
}

function visit(evaluations) {
  cy.intercept('GET', '**/students/teams/1', envelope([active, inactive]))
  cy.intercept('GET', '**/sections/1', envelope({ rubricId: 1 }))
  cy.intercept(
    'GET',
    '**/rubrics/1',
    envelope({
      criteria: [{ criterionId: 1, criterion: 'Quality', description: 'Quality', maxScore: 10 }]
    })
  )
  cy.intercept('GET', '**/evaluations/evaluators/3/week/*', envelope(evaluations)).as('evaluations')
  cy.visit('/evaluations/submit-evaluations', {
    onBeforeLoad(win) {
      const token = `fixture.${win.btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }))}.fixture`
      win.localStorage.setItem('token', JSON.stringify({ token }))
      win.localStorage.setItem(
        'userInfo',
        JSON.stringify({ userInfo: { ...active, roles: 'student' } })
      )
    }
  })
  cy.wait('@evaluations')
}

describe('Inactive evaluation targets (fixture APIs)', () => {
  it('offers only active teammates for new evaluations (T3)', () => {
    visit([])
    cy.get('[data-cy=evaluation-targets]')
      .should('contain', 'John Smith')
      .and('not.contain', 'Eric Wong')
    cy.get('[data-cy=evaluation-submit]').scrollIntoView().should('be.visible')
  })

  it('retains an inactive target for editing the stored evaluation through PUT (T5)', () => {
    cy.intercept('POST', '**/evaluations', envelope(null)).as('create')
    cy.intercept('PUT', '**/evaluations/12', (req) => req.reply(envelope(req.body))).as('update')
    visit([retained])
    cy.get('[data-cy=evaluation-targets]').should('contain', 'Eric Wong')
    cy.get('[placeholder="Enter public comment"]').should('have.value', 'Retained public')
    cy.get('[data-cy=evaluation-update]').click()
    cy.wait('@update').then(({ request }) => {
      expect(request.body.evaluationId).to.eq(12)
      expect(request.body.evaluateeId).to.eq(4)
      expect(request.body.privateComment).to.eq('Retained private')
      expect(request.body.ratings[0].actualScore).to.eq(7)
    })
    cy.get('@create.all').should('have.length', 0)
  })
})
