import { Component, inject, signal } from '@angular/core';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';

@Component({
  selector: 'app-profile',
  imports: [],
  templateUrl: './profile.html',
  styleUrl: './profile.css',
})
export class Profile {
  protected readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);

  protected readonly editing = signal(false);
  protected readonly firstName = signal('');
  protected readonly lastName = signal('');
  protected readonly phoneNumber = signal('');
  protected readonly saving = signal(false);

  startEdit(): void {
    const u = this.auth.user();
    if (!u) return;
    this.firstName.set(u.firstName);
    this.lastName.set(u.lastName);
    this.phoneNumber.set(u.phoneNumber ?? '');
    this.editing.set(true);
  }

  cancelEdit(): void {
    this.editing.set(false);
  }

  async save(): Promise<void> {
    this.saving.set(true);
    try {
      await this.auth.updateProfile({
        firstName: this.firstName(),
        lastName: this.lastName(),
        phoneNumber: this.phoneNumber(),
      });
      this.editing.set(false);
      this.toast.show('Profile updated');
    } finally {
      this.saving.set(false);
    }
  }
}
