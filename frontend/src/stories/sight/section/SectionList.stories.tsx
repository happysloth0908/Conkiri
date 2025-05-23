import type { Meta, StoryObj } from '@storybook/react';
import { SectionList } from '@/components/features/sight/section/SectionList';

const meta = {
  title: 'Features/Sight/Section/SectionList',
  component: SectionList,
  parameters: {
    layout: 'centered',
  },
  tags: ['autodocs'],
} satisfies Meta<typeof SectionList>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  args: {
    arenaId: 1,
    isScrapMode: false,
  },
};

export const ScrapMode: Story = {
  args: {
    arenaId: 1,
    isScrapMode: true,
  },
};
