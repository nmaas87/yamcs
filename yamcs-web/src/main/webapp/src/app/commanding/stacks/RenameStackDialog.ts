import { Component, Inject } from '@angular/core';
import { UntypedFormBuilder, UntypedFormGroup, Validators } from '@angular/forms';
import { MatLegacyDialogRef, MAT_LEGACY_DIALOG_DATA } from '@angular/material/legacy-dialog';
import { ExtensionPipe } from 'src/app/shared/pipes/ExtensionPipe';
import { FilenamePipe } from 'src/app/shared/pipes/FilenamePipe';
import { StorageClient } from '../../client';
import { ConfigService } from '../../core/services/ConfigService';
import { YamcsService } from '../../core/services/YamcsService';
import { BasenamePipe } from '../../shared/pipes/BasenamePipe';

@Component({
  selector: 'app-rename-stack-dialog',
  templateUrl: './RenameStackDialog.html',
})
export class RenameStackDialog {

  filenameForm: UntypedFormGroup;

  private storageClient: StorageClient;
  private bucket: string;

  constructor(
    private dialogRef: MatLegacyDialogRef<RenameStackDialog>,
    formBuilder: UntypedFormBuilder,
    yamcs: YamcsService,
    basenamePipe: BasenamePipe,
    private filenamePipe: FilenamePipe,
    private extensionPipe: ExtensionPipe,
    configService: ConfigService,
    @Inject(MAT_LEGACY_DIALOG_DATA) readonly data: any,
  ) {
    this.storageClient = yamcs.createStorageClient();
    this.bucket = configService.getConfig().stackBucket;

    const basename = basenamePipe.transform(filenamePipe.transform(this.data.name));
    this.filenameForm = formBuilder.group({
      name: [basename, [Validators.required]],
    });
  }

  async rename() {
    let prefix;
    const idx = this.data.name.lastIndexOf('/');
    if (idx !== -1) {
      prefix = this.data.name.substring(0, idx + 1);
    }

    const response = await this.storageClient.getObject('_global', this.bucket, this.data.name);
    const blob = await response.blob();

    const format = this.extensionPipe.transform(this.filenamePipe.transform(this.data.name))?.toLowerCase();

    const newObjectName = (prefix || '') + this.filenameForm.get('name')!.value + (format ? "." + format : '');
    if (newObjectName !== this.data.name) {
      await this.storageClient.uploadObject('_global', this.bucket, newObjectName, blob);
      await this.storageClient.deleteObject('_global', this.bucket, this.data.name);
    }
    this.dialogRef.close(newObjectName);
  }
}
