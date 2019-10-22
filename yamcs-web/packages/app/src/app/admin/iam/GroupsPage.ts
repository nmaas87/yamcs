import { AfterViewInit, ChangeDetectionStrategy, Component, ViewChild } from '@angular/core';
import { FormControl } from '@angular/forms';
import { MatSort } from '@angular/material/sort';
import { MatTableDataSource } from '@angular/material/table';
import { Title } from '@angular/platform-browser';
import { ActivatedRoute, Router } from '@angular/router';
import { GroupInfo } from '@yamcs/client';
import { MessageService } from '../../core/services/MessageService';
import { YamcsService } from '../../core/services/YamcsService';

@Component({
  templateUrl: './GroupsPage.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GroupsPage implements AfterViewInit {

  filterControl = new FormControl();

  @ViewChild(MatSort, { static: true })
  sort: MatSort;

  displayedColumns = [
    'name',
    'members',
    'actions',
  ];
  dataSource = new MatTableDataSource<GroupInfo>();

  constructor(
    private yamcs: YamcsService,
    title: Title,
    private route: ActivatedRoute,
    private router: Router,
    private messageService: MessageService,
  ) {
    title.setTitle('Groups');
    this.dataSource.filterPredicate = (group, filter) => {
      return group.name.toLowerCase().indexOf(filter) >= 0;
    };
  }

  ngAfterViewInit() {
    const queryParams = this.route.snapshot.queryParamMap;
    if (queryParams.has('filter')) {
      this.filterControl.setValue(queryParams.get('filter'));
      this.dataSource.filter = queryParams.get('filter')!.toLowerCase();
    }

    this.filterControl.valueChanges.subscribe(() => {
      this.updateURL();
      const value = this.filterControl.value || '';
      this.dataSource.filter = value.toLowerCase();
    });


    this.refresh();
    this.dataSource.sort = this.sort;
  }

  private refresh() {
    this.yamcs.yamcsClient.getGroups().then(groups => {
      this.dataSource.data = groups;
    });
  }

  private updateURL() {
    const filterValue = this.filterControl.value;
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: {
        filter: filterValue || null,
      },
      queryParamsHandling: 'merge',
    });
  }

  deleteGroup(name: string) {
    if (confirm(`Are you sure you want to delete group ${name}`)) {
      this.yamcs.yamcsClient.deleteGroup(name)
        .then(() => this.refresh())
        .catch(err => this.messageService.showError(err));
    }
  }
}
