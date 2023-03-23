import { Component, Inject } from '@angular/core';
import { UntypedFormBuilder, UntypedFormControl, UntypedFormGroup, Validators } from '@angular/forms';
import { MatLegacyDialogRef, MAT_LEGACY_DIALOG_DATA } from '@angular/material/legacy-dialog';
import { Clearance } from '../../client';

@Component({
  selector: 'app-change-level-dialog',
  templateUrl: './ChangeLevelDialog.html',
  styleUrls: ['./ChangeLevelDialog.css'],
})
export class ChangeLevelDialog {

  form: UntypedFormGroup;

  constructor(
    private dialogRef: MatLegacyDialogRef<ChangeLevelDialog>,
    formBuilder: UntypedFormBuilder,
    @Inject(MAT_LEGACY_DIALOG_DATA) readonly data: any,
  ) {

    this.form = formBuilder.group({
      'level': new UntypedFormControl(null, [Validators.required]),
    });

    if (data.clearance) {
      const clearance = data.clearance as Clearance;
      this.form.setValue({
        level: clearance.level || 'DISABLED',
      });
    }
  }

  confirm() {
    this.dialogRef.close({
      level: this.form.value['level'] === 'DISABLED' ? undefined : this.form.value['level'],
    });
  }
}
