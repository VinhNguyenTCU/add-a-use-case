<template>
  <el-form
    ref="studentForm"
    :model="studentBeingEdited"
    :rules="rules"
    :disabled="busy || expectedEnabled === null"
    label-width="110px"
    style="padding-right: 30px"
    label-position="left"
  >
    <el-form-item label="Id:">
      <el-input v-model="studentBeingEdited.id" disabled></el-input>
    </el-form-item>
    <el-form-item label="Username:" prop="username">
      <el-input v-model="studentBeingEdited.username"></el-input>
    </el-form-item>
    <el-form-item label="Status:" prop="enabled">
      <el-tag v-if="expectedEnabled === true" type="success" data-cy="student-active-status"
        >Enabled</el-tag
      >
      <el-switch
        v-else-if="expectedEnabled === false"
        data-cy="student-reactivate"
        v-model="studentBeingEdited.enabled"
        inline-prompt
        active-text="Enabled"
        inactive-text="Disabled"
        style="--el-switch-on-color: #13ce66; --el-switch-off-color: #ff4949"
      />
    </el-form-item>
    <el-form-item label="First Name:" prop="firstName">
      <el-input v-model="studentBeingEdited.firstName"></el-input>
    </el-form-item>
    <el-form-item label="Last Name:" prop="lastName">
      <el-input v-model="studentBeingEdited.lastName"></el-input>
    </el-form-item>
    <el-form-item label="Email:" prop="email">
      <el-input v-model="studentBeingEdited.email"></el-input>
    </el-form-item>
    <el-form-item label="Team:" prop="teamName">
      <el-input v-model="studentBeingEdited.teamName" disabled></el-input>
    </el-form-item>
    <div style="display: flex; justify-content: end">
      <el-button
        v-if="expectedEnabled === true"
        type="danger"
        plain
        data-cy="deactivate-open"
        :disabled="busy"
        @click="openDeactivation"
        >Deactivate</el-button
      >
      <el-button :disabled="busy" @click="$emit('close-dialog')"> Cancel </el-button>
      <el-button
        type="primary"
        data-cy="student-save"
        :loading="busy"
        @click="updateExistingStudent()"
      >
        Confirm
      </el-button>
    </div>
  </el-form>
  <el-dialog
    v-model="confirmationVisible"
    title="Deactivate this student?"
    width="min(560px, 92vw)"
    append-to-body
    data-cy="deactivate-dialog"
    :show-close="!busy"
    :close-on-click-modal="!busy"
    :close-on-press-escape="!busy"
  >
    <p>
      Deactivating {{ studentBeingEdited.firstName }} {{ studentBeingEdited.lastName }} has these
      consequences:
    </p>
    <ul data-cy="deactivate-consequences">
      <li>
        The student loses login and existing-session access, including submitting weekly activity
        reports and peer evaluations.
      </li>
      <li>The student receives no submission reminders and owes no submissions.</li>
      <li>
        Teammates cannot create new evaluations of the student and no longer need to evaluate the
        student.
      </li>
      <li>
        The account, course section, team membership, and previously submitted work are retained.
      </li>
      <li>
        Existing evaluations remain editable within their submission window. Weekly reports include
        the inactive student only for weeks with received evaluations, retaining scores and
        comments.
      </li>
    </ul>
    <p>You can reactivate the account later. Membership removed separately will not be restored.</p>
    <template #footer>
      <el-button data-cy="deactivate-cancel" :disabled="busy" @click="confirmationVisible = false"
        >Cancel</el-button
      >
      <el-button
        type="danger"
        data-cy="deactivate-confirm"
        :loading="busy"
        :disabled="busy"
        @click="confirmDeactivation"
        >Deactivate student</el-button
      >
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus'
import type { Student } from '@/apis/student/types'
import type { FormInstance } from 'element-plus'
import { findStudentById, updateStudent, deactivateStudent } from '@/apis/student'
import { isAxiosError } from 'axios'
import { ref, onMounted, watch } from 'vue'

const { studentId } = defineProps(['studentId'])
const emit = defineEmits(['close-dialog', 'busy-change'])
const expectedEnabled = ref<boolean | null>(null)
const busy = ref(true)
const confirmationVisible = ref(false)
watch(busy, (value) => emit('busy-change', value), { immediate: true })

// Note that the structure of the userData is NOT the same as the User interface since the roles are stored as an array
const studentBeingEdited = ref<Student>({
  // Data of the user being added or edited
  id: NaN,
  username: '',
  enabled: false,
  firstName: '',
  lastName: '',
  email: ''
})

const studentForm = ref<FormInstance>() // Form reference

// Validation rules
const rules = {
  username: [
    { required: true, message: 'Please input your email as your username', trigger: 'blur' },
    {
      pattern: /^[^\s@]+@[^\s@]+\.[^\s@]+$/,
      message: 'Enter your email as username.',
      trigger: 'blur'
    }
  ],
  enabled: [{ required: true, message: 'Please select the status of the user.', trigger: 'blur' }],
  firstName: [
    { required: true, message: 'Please provide the first name of the user.', trigger: 'blur' }
  ],
  lastName: [
    { required: true, message: 'Please provide the last name of the user.', trigger: 'blur' }
  ],
  email: [
    { required: true, message: 'Please provide the email of the user.', trigger: 'blur' },
    { type: 'email', message: 'Please provide a valid email.', trigger: 'blur' }
  ]
}

async function loadStudent(studentId: number) {
  // Load the user to be edited
  const result = await findStudentById(studentId)

  studentBeingEdited.value.id = result.data.id as number
  studentBeingEdited.value.username = result.data.username
  studentBeingEdited.value.enabled = result.data.enabled
  expectedEnabled.value = result.data.enabled === true
  studentBeingEdited.value.firstName = result.data.firstName
  studentBeingEdited.value.lastName = result.data.lastName
  studentBeingEdited.value.email = result.data.email
  studentBeingEdited.value.teamName = result.data.teamName
}

onMounted(async () => {
  try {
    await loadStudent(studentId)
  } catch {
    /* Shared request client reports the error. */
  } finally {
    busy.value = false
  }
})

async function openDeactivation() {
  if (busy.value) return
  busy.value = true
  try {
    await loadStudent(studentId)
    confirmationVisible.value = expectedEnabled.value === true
  } catch {
    /* Shared request client reports the error. */
  } finally {
    busy.value = false
  }
}

async function confirmDeactivation() {
  if (busy.value) return
  busy.value = true
  try {
    await deactivateStudent(studentId)
    confirmationVisible.value = false
    ElMessage.success('Student deactivated successfully')
    emit('close-dialog')
  } catch {
    // A lost response may have committed. Reload before offering a fresh confirmation.
    confirmationVisible.value = false
    try {
      await loadStudent(studentId)
    } catch {
      expectedEnabled.value = null
    }
  } finally {
    busy.value = false
  }
}

async function updateExistingStudent() {
  if (busy.value) return
  const valid = await studentForm.value!.validate().catch(() => false)
  if (!valid) return
  busy.value = true
  try {
    await updateStudent({ ...studentBeingEdited.value }, expectedEnabled.value ?? undefined)
    ElMessage.success('Student updated successfully')
    emit('close-dialog')
  } catch (error) {
    if (isAxiosError(error) && error.response?.status === 409) {
      try {
        await loadStudent(studentId)
      } catch {
        expectedEnabled.value = null
      }
    }
  } finally {
    busy.value = false
  }
}
</script>

<style scoped lang="scss"></style>
